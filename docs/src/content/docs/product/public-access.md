---
title: Public access
description: Publish read-only calendar links and control who can open them.
---

A public calendar is a calendar with an unguessable link that anyone can open
without an account, subject to the visibility rules below. The link is
read-only and can be revoked at any time.

## Publish a calendar

1. Right-click the calendar in the sidebar and choose **Share…**.
2. Turn on **Public read-only link**.
3. Copy the link, which looks like `https://your-server/c/<token>`.

From the same dialog you can:

- **Rotate link** — issues a new token. The old link stops working
  immediately; use this if a link leaks.
- Turn the toggle **off** — disables the public page entirely.

A public calendar page shows the calendar in read-only **Month** and **Week**
views. If the owner has enabled time requests, the page also offers
**Request a time…** (see
[Availability and scheduling](/docs/product/availability-and-scheduling)).

## RSS feed

Every public calendar has an RSS feed at `/rss/<token>.xml`. It lists events in
the next 90 days and follows the same visibility rules as the calendar page.
The feed is available whenever the public link is enabled; there is no separate
toggle for it yet.

## Who can open a public link

There are three settings that decide whether an anonymous visitor or a
signed-in user can open a public calendar. They resolve in order, from most
specific to least:

1. **Calendar** — the calendar's own **Who can view** setting, from its
   **Office hours** dialog.
2. **User** — the owner's account-level **Privacy** setting
   (**Settings → My Account → Privacy**).
3. **Instance** — the server default set by an administrator under
   **Admin → Privacy**, configured with `app.publicAccess`.

Each level can be:

| Value | Meaning |
| --- | --- |
| `public` | Anyone with the link, including anonymous visitors. |
| `signed_in` | Only people signed in to this instance. |
| `inherit` | Use the next level up (not valid at the instance level). |

The first non-`inherit` value wins. If every level inherits, the built-in
default is `public`.

Two additional rules apply:

- A calendar owned by a **private organization** is always treated as
  `signed_in`, and non-members cannot open it even when signed in. Public
  organizations do not impose this restriction.
- The public-link token is a capability: anyone who has it and passes the
  visibility check can view the calendar. It is read-only, so it cannot be used
  to edit anything.

## What visitors see

- **Anonymous** visitors get the read-only calendar grid and can submit a time
  request or RSVP when the owner has enabled those and the calendar is
  `public`.
- **Signed-in** users get the same page plus **Follow**, which adds the
  calendar to their sidebar. See
  [Sharing and following](/docs/product/sharing-and-following).
- The calendar owner is identified by display name, and the calendar's
  description is shown.

Public calendars also appear in the [directory](/docs/product/directory-and-profiles)
and on the owner's profile at `/u/{username}`.

## Related

- [Sharing and following](/docs/product/sharing-and-following)
- [Availability and scheduling](/docs/product/availability-and-scheduling)
- [Directory and profiles](/docs/product/directory-and-profiles)
- [Accounts and security](/docs/product/accounts-and-security)
