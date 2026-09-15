---
title: Getting started
description: Create an account, find your way around the week view, and add your first event.
---

This guide takes you from a fresh Kalendee instance to a calendar with an event
on it. It assumes the instance is already running and you have its URL.

## Create an account

Whether you can register depends on the server's registration policy, which an
administrator controls:

- **Open** — anyone can create an account from the register page.
- **First user** (the default) — registration is open until the first account
  exists, then it closes. On a new instance you can register immediately.
- **Closed** — registration is disabled. Use the account an administrator
  created, or ask them to open registration.

To register:

1. Open `/register`.
2. Enter a **username**. It is 3–32 characters, must start with a letter or
   digit, and may contain letters, digits, dots, underscores, and hyphens. It is
   stored lowercase and cannot be changed later.
3. Enter a **password** of at least 8 characters (up to 128).
4. Optionally enter an **email** address. Some servers require it; adding one
   enables verification, invite emails, and notifications.
5. Select **Create account**. If email verification is not enforced you are
   signed in immediately; otherwise you are asked to check your email first.

The first account on a server that was seeded with an admin password is the
admin account. If you are running your own instance, see the self-hosting
documentation for the admin seed.

## Sign in and out

1. Open `/login` and enter your username and password.
2. Kalendee sets a session cookie (`kalendee_session`) that lasts 30 days by
   default.

If the server requires a verified email, an unverified account is held at the
login screen with a **Resend verification email** button until the address is
confirmed.

To sign out, open the account menu in the top-right corner and choose
**Sign out**, or use **Log Out** at the bottom of Settings.

## Email verification

Servers choose one of three policies:

| Policy | Behavior |
| --- | --- |
| **Required** | You must verify your email before you can sign in. Registration holds the account until the link is opened. |
| **Soft** | You can sign in right away, but the app shows a banner reminding you to verify. |
| **Optional** | Verification is entirely your choice; add and verify an email from Settings whenever you like. |

Verification links expire after 24 hours by default; use **Resend verification
email** if yours has lapsed.

## The week view

After signing in you land on `/`, the calendar workspace. It has:

- A **sidebar** on the left listing your calendars (grouped under Personal and
  any organizations), friends, and a **Show holidays** toggle.
- A **toolbar** with **Previous / Today / Next**, the current date label, and
  **Day**, **Week**, and **Month** tabs.
- A **grid** showing the selected range. The current time has a red line, and
  today's column is highlighted.

Things worth knowing:

- The view follows your **browser time zone**; the URL carries a `tz`
  parameter so a shared link shows the same times.
- The eye button next to a calendar hides or shows it without deleting
  anything.
- Events are colored by the calendar they belong to.
- Holidays appear on the grid only when **Show holidays** is on; clicking one
  opens holiday settings.

## Create your first event

1. Hover over the time you want and drag down to draw a 30-minute block (times
   snap to 15-minute steps).
2. Release to open the **New event** dialog, or drag a block and edit it before
   saving.
3. Fill in the **Title**, leave the calendar selected, and set the start and
   end. Toggle **All day** for a full-day event.
4. Optionally add **Notes** and a **Link**, set the event to **Repeat**, and
   adjust **Reminders**.
5. Select **Create**.

You can also right-click an empty slot on the grid and choose **New event**, or
click an all-day cell to create an all-day event.

## Import calendars and subscribe to others

- **Follow a public calendar.** Open a public calendar link (`/c/{token}`) and
  select **Follow**. It appears in your sidebar as a read-only calendar and
  updates when the owner changes it.
- **Import Discord server events.** If the server has the Discord integration
  configured, connect an account under **Settings → Connected Accounts** and
  import a server's scheduled events into a read-only calendar. See
  [External calendars](/docs/product/external-calendars).
- **Show holidays.** Enable the catalog under **Settings → Holidays** and toggle
  **Show holidays** in the sidebar.

Linking Google, Microsoft, Apple iCloud, generic CalDAV, and ICS calendar
accounts is planned and not available yet; see
[External calendars](/docs/product/external-calendars).

## Next steps

- Create more calendars and give them colors in
  [Calendars and events](/docs/product/calendars-and-events).
- Set up repeating meetings in [Recurring events](/docs/product/recurring-events).
- Share a calendar or publish a read-only link in
  [Sharing and following](/docs/product/sharing-and-following) and
  [Public access](/docs/product/public-access).
- Let people book time with you in
  [Availability and scheduling](/docs/product/availability-and-scheduling).

## Related

- [Overview](/docs/product/index)
- [Calendars and events](/docs/product/calendars-and-events)
- [Accounts and security](/docs/product/accounts-and-security)
- [Notifications](/docs/product/notifications)
