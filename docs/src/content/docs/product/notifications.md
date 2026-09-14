---
title: Notifications
description: In-app activity notifications, unread badges, and browser reminders.
---

Kalendee has two separate notification systems: **activity notifications** for
things that happened in your account, and **event reminders** that fire before
an event starts. They appear in different places and are configured
differently.

## Activity notifications

The bell icon in the top bar shows an unread count (capped at `9+`). Select it
to open `/notifications`, which lists recent activity newest-first.

- Opening a notification marks it read and follows its link to the relevant
  page.
- **Mark all as read** clears the badge.
- Notifications are stored per user; signing out does not lose them.

### What triggers a notification

| Kind | Sent to | When |
| --- | --- | --- |
| `calendar_shared` | The invitee | Someone shares a calendar with them. |
| `new_follower` | The calendar owner | Someone follows their public calendar. |
| `following` | The follower | They follow a public calendar. |
| `friend.request` | The recipient | Someone sends a friend request. |
| `friend.accepted` | The requester | Their friend request is accepted. |
| `org.invite` | The invitee | They are invited to an organization. |
| `event.invite` | The invitee | They are invited to an event. |
| `event.rsvp` | The event owner | An attendee responds yes / no / maybe. |
| `slot.request` | The calendar owner | Someone requests a time slot. |
| `slot.accepted` / `slot.declined` | The requester | The owner accepts or declines a time request. |

Many of these also send email. Email requires the operator to configure a mail
transport; when mail is disabled, the server logs messages and their links
instead. Account verification and new-sign-in security alerts are email-only
and do not appear in the in-app list.

## Event reminders

Reminders are configured in **Settings → Notifications** and overridden per
event.

### Defaults

- **Default reminders** apply to new events. Each reminder is a value and a
  unit — minutes, hours, or days before the event. You can have up to **10**
  reminders, each at most one year before the event.
- **Notify when an event starts** adds a notification at the event's start time
  in addition to the offsets above.
- An individual event can either use the defaults or set its own offsets in the
  event dialog.

### Delivery: browser notifications

Reminders are delivered as **browser notifications** while Kalendee is open in
that browser:

1. Open **Settings → Notifications**.
2. Select **Enable browser notifications** and grant the browser permission.
3. Use **Send test notification** to confirm it works.

The web app polls for upcoming reminders and shows a desktop notification when
one is due; selecting the notification opens that day in the calendar. This
only works while the app is open in a browser tab, and permission is per
browser. Notifications are deduplicated so a reminder does not fire twice.

OS-level native notifications on Android, iOS, and Desktop are planned and not
available yet; see
[GOALS.md](https://github.com/KolektivComputer/kalendee/blob/main/GOALS.md).

## Related

- [Calendars and events](/docs/product/calendars-and-events)
- [Event invites and RSVP](/docs/product/event-invites-and-rsvp)
- [Availability and scheduling](/docs/product/availability-and-scheduling)
- [Accounts and security](/docs/product/accounts-and-security)
