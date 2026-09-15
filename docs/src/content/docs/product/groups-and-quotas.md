---
title: Groups and quotas
description: Admin-managed user groups, storage quotas, and how limits resolve.
---

Groups are an administrator tool for organizing accounts and setting storage
limits. They are different from [organizations](/docs/product/organizations) and
teams, which users create themselves to share calendars.

Groups are managed in **Admin → Groups** by an administrator.

## System groups

Two groups always exist and cannot be renamed or deleted:

| Group | Membership | Purpose |
| --- | --- | --- |
| `default` | Every user, implicitly | The base group. Its quota acts as the instance-wide default. |
| `admin` | Every administrator, kept in sync automatically | A convenient view of all admins. |

Because `default` membership is implicit, its member list cannot be edited.
Because `admin` mirrors the admin flag, adding or removing someone from it
promotes or demotes them rather than storing a separate list.

## Custom groups

Administrators can create any number of additional groups.

1. In **Admin → Groups**, select **New group**.
2. Give it a name (1–64 characters) and optionally a **storage quota**.
3. Select **Create**.

From the group's row you can **Edit quota** (set a limit or clear it so it is
unlimited), manage **Members**, or **Delete** it. Names are unique and the
reserved names `default` and `admin` are rejected.

## How quotas work

A group's quota is a byte limit on stored data. Quotas compose as follows:

- A user's **effective quota** is the **lowest** non-unlimited quota among the
  `default` group and every custom group they belong to.
- If none of a user's groups has a quota, the user is **unlimited**.
- **Admins and superadmins are always unlimited**, regardless of group quotas.
- A user belongs to `default` implicitly, so a quota on `default` is the
  instance-wide default for everyone else.

The current admin UI shows each user's usage and effective quota in
**Admin → Users**, and each group's quota in **Admin → Groups**.

### What storage covers today

Storage usage is measured from **avatar uploads** (the only stored media at the
moment). An upload that would push a user over their quota is rejected with
`storage quota exceeded`. Object storage for avatars can be a local directory or
an S3-compatible bucket, configured by the operator.

Attachments and other media are not stored yet; the quota mechanism is in place
for when they are. See
[GOALS.md](https://github.com/KolektivComputer/kalendee/blob/main/GOALS.md) for
the media-storage direction.

## Administrators and superadmin

- An **admin** can reach `/admin` and manage users, calendars, groups,
  registration, and instance privacy.
- A **superadmin** is designated by the operator with
  `KALENDEE_SUPERADMIN_USERNAME`. A superadmin is always an admin and cannot be
  demoted. Only a superadmin can delete another superadmin.

Admins cannot remove their own admin access. Demoting another admin requires a
superadmin; the `admin` group membership is updated to match.

## Related

- [Organizations](/docs/product/organizations)
- [Accounts and security](/docs/product/accounts-and-security)
- [Directory and profiles](/docs/product/directory-and-profiles)
- [Calendars and events](/docs/product/calendars-and-events)
