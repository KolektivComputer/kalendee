---
title: Administration
description: >-
  Seed the first admin, grant superadmin, control registration, manage users and
  groups with storage quotas, and inspect the database.
---

## Seed the first admin

On every startup the server calls an idempotent `seedAdmin` routine using
`auth.adminUsername` and `auth.adminPassword`:

| Key | Env fallback | Default | Behavior |
| --- | --- | --- | --- |
| `auth.adminUsername` | `KALENDEE_ADMIN_USERNAME` | `admin` | Username to create or promote. |
| `auth.adminPassword` | `KALENDEE_ADMIN_PASSWORD` | *(blank)* | When blank, seeding is skipped entirely. |
| `auth.superadminUsername` | `KALENDEE_SUPERADMIN_USERNAME` | *(blank)* | Existing user to grant superadmin (see below). |

The routine:

- creates the user as an admin if it does not exist;
- changes its password if the configured value no longer matches;
- promotes it to admin if it exists without admin rights.

Because it runs on every startup, it is also the supported way to reset the
admin password: set `auth.adminPassword` to a new value, restart, then remove
the value from the environment if you do not want it retained.

The username must match Kalendee's account rules (3–32 characters, start with a
letter or digit, letters/digits/`.`/`_`/`-`, stored lowercase), and the password
must be 8–128 characters. An invalid value aborts startup with an
`Invalid` error.

If you never set an admin password, the first user to register becomes an admin
(see [Registration policy](#registration-policy)). See
[Accounts and security](/docs/product/accounts-and-security) for the user-facing
model.

## Superadmin

A **superadmin** is an admin who cannot be demoted by other admins. Set
`auth.superadminUsername` (env `KALENDEE_SUPERADMIN_USERNAME`) to an existing
username; at startup the server sets both `is_admin` and `is_superadmin` on that
user. The user must already exist — the module promotes, it does not create.

Authorization rules enforced by the server:

- Only a superadmin can demote another admin, delete a superadmin, or modify a
  superadmin's account.
- A superadmin cannot be demoted, including by another superadmin.
- No admin can remove their own admin rights.
- Admins are members of the system `admin` group automatically.

Keep the superadmin username out of the general config if possible; it is a
privileged identity. All admins can manage users, calendars, and groups through
the API; the superadmin difference is only about demotion/deletion.

## Registration policy

`auth.registration` (env `KALENDEE_AUTH_REGISTRATION`) accepts:

| Value | Meaning |
| --- | --- |
| `first-user` (default) | Public registration is open only while the user table is empty. Seeding an admin closes it. |
| `open` | Anyone may register. |
| `closed` | Registration is disabled; admins create accounts. |

Admins can override the configured policy at runtime from the admin UI
(`/admin`), which writes an `app_settings` row (key `registration`,
`oauth_registration`, or the email-verification policy). The database override
wins over the HOCON value. To return to the file-based policy, clear the
corresponding `app_settings` row.

Related settings:

| Key | Values | Default | Effect |
| --- | --- | --- | --- |
| `auth.emailVerification` | `required`, `optional`, `off` | `optional` | Whether new accounts must verify an email address. |
| `auth.emailVerificationTtlHours` | integer hours | `24` | Verification link lifetime. |
| `auth.oauthRegistration` | `true` / `false` | `false` | Whether OAuth sign-ins may create accounts. Still gated by the registration policy. |

`required` means an email address must be supplied and verified before the
account can be used; this needs working mail. See
[Email](/docs/self-hosting/email).

## Users and invitations

There is no separate invite system. To let someone in, either open registration
temporarily, ask them to register and then close it, or create the account for
them.

Admins manage users in the admin UI at `/admin` or through the JSON API:

| Action | API |
| --- | --- |
| List users | `GET /api/v1/admin/users` |
| Edit a user (display name, email, password, admin flag) | `PATCH /api/v1/admin/users/{id}` |
| Delete a user | `DELETE /api/v1/admin/users/{id}` |
| List all calendars | `GET /api/v1/admin/calendars` |
| Delete any calendar | `DELETE /api/v1/admin/calendars/{id}` |
| Toggle a calendar's public link | `PATCH /api/v1/admin/calendars/{id}` |

Editing an email address clears its verified flag and sends a new verification
mail. Deleting a user cascades to their sessions, calendars, events, shares, and
connections. An admin cannot delete themselves.

## Groups and storage quotas

Kalendee has two **system** groups and any number of custom groups:

| Group | Membership | Notes |
| --- | --- | --- |
| `default` | Every user, implicitly | Cannot be deleted or renamed. Its quota applies to everyone as a baseline. |
| `admin` | Every admin, kept in sync with the admin flag | Cannot be renamed or edited as a normal group. |
| Custom groups | Assigned by an admin | Name up to 64 characters (`A–Z`, `0–9`, space, `.`, `_`, `-`). |

Quotas are storage quotas, and today they cover **avatar bytes only**
(`users.avatar_bytes`). The effective quota for a user is the **minimum** of the
`default` group's quota and every explicit group quota that has a value; a
`NULL` quota means "no limit from this group". Admins and superadmins are exempt
and always unlimited.

- A quota of `NULL` (unset) on `default` and all groups means unlimited storage.
- Set a quota on `default` to cap every non-admin by default.
- A smaller quota on a specific group tightens the cap for its members.

Manage groups via the admin UI or the API:

| Action | API |
| --- | --- |
| List groups | `GET /api/v1/admin/groups` |
| Create a group | `POST /api/v1/admin/groups` with `{ "name": "...", "storageQuotaBytes": 104857600 }` |
| Update name/quota | `PATCH /api/v1/admin/groups/{id}` (`clearQuota: true` removes the limit) |
| Delete a group | `DELETE /api/v1/admin/groups/{id}` |
| List members | `GET /api/v1/admin/groups/{id}/members` |
| Replace members | `PUT /api/v1/admin/groups/{id}/members` with `{ "userIds": ["..."] }` |

`storageQuotaBytes` is a byte count; `104857600` is 100 MiB. Uploading an
avatar that would exceed the effective quota returns `403 storage quota
exceeded`.

## Inspecting the database

Use the database directly for diagnostics, not for routine administration.
With Compose:

```bash
docker compose exec postgres \
    psql -U "${POSTGRES_USER:-kalendee}" -d "${POSTGRES_DB:-kalendee}"
```

Useful queries:

```sql
-- Users, admin/superadmin flags, verified email, avatar usage.
SELECT username, display_name, is_admin, is_superadmin, email_verified, avatar_bytes
FROM users ORDER BY created_at;

-- Runtime setting overrides written by the admin UI.
SELECT key, value FROM app_settings;

-- Applied migrations.
SELECT version, description, success, installed_on
FROM flyway_schema_history ORDER BY installed_rank;

-- Groups and quotas (NULL = unlimited).
SELECT name, is_system, storage_quota_bytes FROM user_groups ORDER BY name;

-- Members of a group.
SELECT u.username FROM users u
JOIN user_group_members m ON m.user_id = u.id
JOIN user_groups g ON g.id = m.group_id
WHERE g.name = 'staff';
```

Granting admin or superadmin directly in SQL bypasses startup logic; prefer the
environment variables and a restart so the in-process group sync runs:

```sql
UPDATE users SET is_admin = TRUE WHERE username = 'alice';
UPDATE users SET is_admin = TRUE, is_superadmin = TRUE WHERE username = 'root';
```

## Common tasks

| Task | How |
| --- | --- |
| Reset the admin password | Set `KALENDEE_ADMIN_PASSWORD` to the new value and restart. |
| Create another admin | Admin UI → edit the user → admin; or set the target's `is_admin`. |
| Close registration | Admin UI toggle, or set `auth.registration = "closed"`. |
| Cap storage for everyone | Set a quota on the `default` group. |
| Re-open a locked-out account | Reset the password via the admin UI. |
| Recover from a lost `KALENDEE_SECRET_KEY` | Not possible for existing tokens; users must re-link external calendars. |
