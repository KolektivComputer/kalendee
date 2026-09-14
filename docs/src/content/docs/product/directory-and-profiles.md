---
title: Directory and profiles
description: Find people, organizations, and public calendars, and manage your own profile.
---

Kalendee includes a public directory and a profile page for every account. They
are optional surfaces: people appear once they publish a calendar, and
organizations appear once they are made public.

## The directory

`/directory` lists three things:

- **Organizations** — every organization whose visibility is public.
- **People** — accounts on the instance.
- **Public calendars** — every calendar with its public link enabled and
  visible under the access rules.

Signed-in users can create an organization from this page with **New
organization** and then choose its visibility; see
[Organizations](/docs/product/organizations).

Access to the directory follows the instance privacy setting:

- When the instance is `public`, anyone can browse it.
- When the instance is `signed_in`, visitors must sign in first.

The directory has no search box; it lists the entities that are visible to you.
The admin console has its own search over users and calendars.

## Profiles

Every account has a page at `/u/{username}`. It shows:

- Display name and `@username`.
- **Public calendars** the person owns that are visible to you, each linked to
  its public page.
- **Public organizations** the person belongs to.

A profile does not show private information such as an email address, hidden
calendars, private organizations, or calendars the person does not own.

Who can open a profile also follows the instance privacy setting: anonymous
visitors can open profiles where the person's resolved access is `public`,
while signed-in users can open profiles on the instance.

## Edit your profile

Profile details live in **Settings → My Account**:

| Field | Notes |
| --- | --- |
| Display name | Shown in the sidebar, directory, and on shared calendars. Up to 80 characters. |
| Username | Fixed at registration; it is the `/u/{username}` address. |
| Time zone | Your display default. |
| Email | Optional or required depending on the server; changing it requires re-verification. |
| Accent color | Your theme accent; see [Theming](/docs/product/theming). |
| Photo | An uploaded avatar. |

### Avatar

- Supported formats: PNG, JPEG, WebP, and GIF.
- Maximum size: **2 MiB**.
- Upload or remove the photo from **Settings → My Account**. Removing it falls
  back to initials.

Avatars are stored server-side (locally or in S3-compatible object storage) and
served through the server, so clients never hold storage credentials. Avatar
uploads count toward your [storage quota](/docs/product/groups-and-quotas).

To have anything on your profile, publish a calendar first: profiles only list
**public** calendars and **public** organizations.

## Related

- [Public access](/docs/product/public-access)
- [Organizations](/docs/product/organizations)
- [Theming](/docs/product/theming)
- [Accounts and security](/docs/product/accounts-and-security)
