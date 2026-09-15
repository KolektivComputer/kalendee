---
title: Availability and scheduling
description: Publish office hours and let people request a time on your calendar.
---

Kalendee's scheduling feature lets a calendar owner publish **office hours**
and lets other people **request a time slot** inside them. Accepting a request
creates a real event on the calendar.

## Configure office hours

Only the calendar owner can change availability.

1. Right-click the calendar in the sidebar and choose **Office hours…**.
2. Turn on **Accept time requests**.
3. Choose a **Slot length** — 15, 30, 45, 60, 90, or 120 minutes.
4. Choose **Who can view**: inherit the account/instance default, **Anyone with
   the link** (`public`), or **Signed-in users only** (`signed_in`).
5. Add availability windows: for each weekday, one or more start/end ranges.
6. Select **Save**.

Windows cannot overlap on the same day, and there can be at most 50 of them.
Times use the calendar's time zone. The **Who can view** setting is the same
access mode covered in [Public access](/docs/product/public-access), so it
applies even if you only use the calendar for visibility and not scheduling.

## How a slot is validated

When someone requests a slot, Kalendee checks that it:

- Starts in the future.
- Is at least one slot length long and at most **8 hours** long.
- Fits entirely inside a configured office-hours window for that weekday.
- Does not overlap any event on **any calendar the owner owns** — so a busy
  owner is never double-booked.
- Does not duplicate a pending request from the same requester for the same
  window.

The requester picks from the available slots Kalendee computes, so most of
these rules are enforced before they can submit.

## Request a time

### As a signed-in user

You must have at least `read` or `follow` access to the calendar.

1. Select the calendar in the sidebar.
2. In the toolbar, select **Request a time…** (it appears when the calendar
   accepts requests).
3. Pick a day (today up to about two months ahead) and one of the offered
   times.
4. Optionally add a message and select **Send request**.

The owner gets an in-app notification and, if email is configured for their
account, a request email.

### As an anonymous visitor

If the calendar is public and accepts requests, its page at `/c/{token}` shows
**Request a time…**. Anonymous requesters must give a **name**; an email is
optional but recommended so the owner's decision can reach them. With an email,
repeat requests for the same window are deduplicated; without one they cannot
be told apart.

## Review requests

The owner manages requests from the calendar menu:

1. Right-click the calendar and choose **Time requests…**.
2. Review the list. Each entry shows the requester, the slot, and any message,
   with a **Pending**, **Accepted**, or **Declined** status.
3. Select **Accept** or **Decline**.

- **Accept** creates an event titled **Meeting with \<name\>** on the calendar,
  copying the requested time and message.
- The requester is notified in-app, and by email when an address is known.
- The number of pending requests appears as a badge on the calendar's menu.

## Scope

This is a single-slot request flow: a visitor proposes one concrete time and
the owner accepts or declines it. Proposing several candidate times, or letting
a visitor book automatically without approval, is not part of the current
feature. The broader scheduling direction is in
[GOALS.md](https://github.com/KolektivComputer/kalendee/blob/main/GOALS.md).

For inviting specific people to an event you already created, see
[Event invites and RSVP](/docs/product/event-invites-and-rsvp).

## Related

- [Calendars and events](/docs/product/calendars-and-events)
- [Public access](/docs/product/public-access)
- [Event invites and RSVP](/docs/product/event-invites-and-rsvp)
- [Notifications](/docs/product/notifications)
