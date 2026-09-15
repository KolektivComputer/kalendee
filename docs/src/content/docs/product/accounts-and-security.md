---
title: Accounts and security
description: Registration policies, sign-in, sessions, passwords, admin powers, and Discord OAuth.
---

This page documents how accounts work on a Kalendee instance: who can create
one, how sign-in and sessions behave, and what administrators can do. Server
operators should also read the self-hosting configuration docs for the exact
environment keys.

## Registration

Registration is governed by a policy that an administrator can change while the
server runs:

| Policy | Who can register |
| --- | --- |
| **Open** | Anyone who can reach the register page. |
| **First user** (default) | Anyone until the first account exists; then registration closes automatically. |
| **Closed** | No one. |

Administrators toggle open/closed in **Admin → Registration**; the shipped
default comes from `auth.registration` (or `KALENDEE_AUTH_REGISTRATION`). An
operator can seed the first admin account with a password so a fresh instance is
reachable even before anyone registers.

## Email verification

Email is optional unless the server requires it. The verification policy is:

| Policy | Behavior |
| --- | --- |
| `required` | Sign-in is blocked until the address is verified. |
| `soft` | Sign-in works, but a banner reminds the user to verify. |
| `optional` | Verification is entirely optional. |

- Configured with `auth.emailVerification` and changed at runtime under
  **Admin → Registration**.
- Verification links expire after `auth.emailVerificationTtlHours` (24 by
  default); the page offers a resend.
- Verified email is what enables calendar-share emails, event invites, and
  security alerts, and it is the key Discord/OAuth sign-in uses to match an
  existing account.

## Passwords

- Passwords are 8–128 characters and are hashed with **Argon2id**.
- The hash parameters default to 19,456 KiB of memory, 2 iterations, and
  parallelism 1, configurable through the `auth.argon2.*` settings.
- A user sets the password at registration. **There is no self-service change
  or reset flow in the web UI yet**; an administrator resets a password from
  **Admin → Users → Edit**.

## Sessions

- Sign-in sets a `kalendee_session` cookie: `HttpOnly`, `SameSite=Lax`, and
  `Secure` outside development.
- The session lifetime is 30 days by default (`auth.sessionDays`).
- Only a hash of the session token is stored server-side; the raw token lives
  in the cookie and is cleared on sign-out.
- Sessions are looked up on each request and expired ones are removed.

### Login throttling

Repeated failed sign-ins from the same source are throttled. After 5 failures
within 60 seconds, further attempts are rejected with a "too many login
attempts" error until the window resets. A successful sign-in clears the
counter.

### New sign-in alerts

When an account with a verified email signs in from a new device, Kalendee
emails a security alert describing the new sign-in. It fires automatically and
requires the operator to have configured outgoing mail.

## Discord OAuth

Kalendee supports Discord as an OAuth provider.

- **Connect an account.** Signed-in users add Discord under
  **Settings → Connected Accounts**. This links the Discord account and enables
  the read-only Discord server-event import; see
  [External calendars](/docs/product/external-calendars).
- **Sign in / register with Discord.** The sign-in page offers Discord when
  `oauth.discord.enabled` is configured. Signing in creates or matches an
  account as follows:
  - If the Discord account's **verified email** matches an existing user, that
    user is signed in.
  - Otherwise the user is created, but only when OAuth registration is enabled
    (`auth.oauthRegistration`, default on) **and** normal registration is open.
  - If the email already belongs to an account that is not verified, the sign-in
    is refused rather than merging accounts.
- Discord refresh tokens are encrypted at rest with a key from
  `oauth.secretKey`; a rejected refresh marks the connection as needing
  re-authentication.

Google, Microsoft, and other OAuth providers are not implemented; see
[External calendars](/docs/product/external-calendars).

## Administrators and the superadmin

Administrators (`/admin`) can:

- Search, edit, and delete users, including setting a new password and toggling
  the admin flag.
- Delete calendars and disable their public links.
- Manage groups and storage quotas; see
  [Groups and quotas](/docs/product/groups-and-quotas).
- Change registration, email verification, and instance public-access settings.
- Open another user's calendar in read-only mode for support.

A **superadmin** is designated by the operator with
`auth.superadminUsername` (`KALENDEE_SUPERADMIN_USERNAME`). A superadmin cannot
be demoted, and only a superadmin can delete another superadmin. Admins cannot
remove their own admin access.

## Instance privacy

The instance default for [public access](/docs/product/public-access) is
`app.publicAccess` (`public` or `signed_in`). It can be changed at runtime under
**Admin → Privacy**, and per-user and per-calendar settings can override it.

## Related

- [Getting started](/docs/product/getting-started)
- [Public access](/docs/product/public-access)
- [Groups and quotas](/docs/product/groups-and-quotas)
- [External calendars](/docs/product/external-calendars)
