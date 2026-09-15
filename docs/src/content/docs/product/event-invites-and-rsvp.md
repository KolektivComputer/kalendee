---
title: Event invites and RSVP
description: Invite users or email addresses to an event and collect yes / no / maybe replies.
---

Kalendee lets you invite people to a single event and collect their response.
Invitations work for both signed-in users on the instance and for people
without an account, who respond through a link.

## Invite attendees

You need write access to the event's calendar.

1. Open the event to edit it.
2. Find the **Attendees** section (shown for events you can edit).
3. Enter a **username or email** and select **Invite**.

What happens depends on what you entered:

- **Username** — the person must already have an account. They cannot already
  be invited, and you cannot invite yourself. They receive an in-app
  notification and, if the server sends mail to their verified address, an
  invite email.
- **Email address** — the address does not need an account. Kalendee records
  the attendee with a one-time token and emails them an RSVP link of the form
  `/rsvp/<eventId>?token=<token>`.

Attendees appear in the list with their current status and a **Remove** button.
Removing an attendee deletes the invite; it does not remove any event.

An invitation is an RSVP record. It does not by itself share the calendar, so a
user invitee can respond from the event once they can see it (for example, the
calendar is shared with them or public).

## RSVP statuses

| Status | Meaning |
| --- | --- |
| `invited` | Invited, no response yet. |
| `yes` | Attending. |
| `no` | Not attending. |
| `maybe` | Tentative. |

The owner is notified in-app whenever someone responds, and by email when the
owner has an address. Attendee responses are visible to anyone who can see the
event.

## Respond to an invitation

### Signed-in users

Anyone who can see an event may respond, not only the people explicitly
invited. Open the event and use the **Your response** buttons: **Yes**, **No**,
or **Maybe**. Their response is stored and shown as a badge.

### Invitees by email

Open the link from the invitation email. The page shows the event title, when
it is, and the calendar, with **Yes / No / Maybe** buttons. Email invitees do
not need to sign in.

### Open RSVP links

An event owner can turn an event into an open, link-based RSVP:

1. In the event's **Attendees** section, turn on **Open RSVP**.
2. Copy the resulting link, which looks like `/rsvp/<eventId>`.

Anyone with that link can respond. Anonymous visitors must enter a **name**;
signed-in users respond under their account. An email is optional and lets a
returning visitor update their existing response.

Anonymous access follows the same rules as public calendars: the event must be
on a `public` calendar. On a `signed_in` calendar, only signed-in visitors can
respond. A calendar that mirrors an external provider is read-only and cannot
enable Open RSVP.

## Related

- [Calendars and events](/docs/product/calendars-and-events)
- [Availability and scheduling](/docs/product/availability-and-scheduling)
- [Public access](/docs/product/public-access)
- [Notifications](/docs/product/notifications)
