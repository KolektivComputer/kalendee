---
title: Calendars and events
description: Create and organize calendars, and manage the events on them.
---

A Kalendee account owns one or more **calendars**, and every event belongs to
exactly one calendar. This page covers calendar management, the fields on an
event, how time zones work, and how to edit or remove things.

## Calendars

### Create a calendar

1. In the sidebar, select the **+** next to **Calendars**.
2. Enter a **Name** and optional **Description**.
3. Pick a **Color** from the palette.
4. Set the **Time zone** (defaults to your account time zone).
5. Select **Create**.

Calendar names must not be blank. The time zone must be a valid IANA zone such
as `Europe/Berlin` or `America/New_York`.

### Calendar fields

| Field | Notes |
| --- | --- |
| Name | Required; shown in the sidebar and on public pages. |
| Description | Optional; shown on the calendar's public page. |
| Color | One of the theme palette entries: primary, secondary, accent, info, success, warning, error. |
| Time zone | The calendar's default zone, used when an event has no zone of its own. |
| Hidden | Per-user toggle; hides the calendar's events without deleting anything. |
| Public link | Off by default; see [Public access](/docs/product/public-access). |

### Edit, hide, or delete

- Right-click a calendar in the sidebar (or use the row menu) and choose
  **Edit** to rename it, change its description, color, or time zone.
- Use the **eye** button on the row to hide or show it. Hidden calendars stay in
  the sidebar and keep their events.
- Choose **Delete** to remove the calendar. Every event on it is deleted and
  this cannot be undone.

### Organize calendars

Calendars you own are grouped in the sidebar:

- **Personal** — calendars not attached to an organization.
- **Organizations** — calendars owned by an organization, optionally grouped by
  team.

You can move a calendar into an organization or team with **Move to…** in its
menu, or by dragging it onto an organization in the sidebar. This is covered in
[Organizations](/docs/product/organizations).

## Events

### Event fields

The **New event** / **Edit event** dialog exposes:

| Field | Notes |
| --- | --- |
| Title | Required. |
| Calendar | Which calendar the event lives on (set at creation). |
| All day | Makes the event span whole days. |
| Starts / Ends | Date, and time for timed events. Grid times snap in 15-minute steps. |
| Notes | Free text, shown on the event. |
| Link | A URL starting with `http://` or `https://`, up to 2048 characters. |
| Repeats | Turns the event into a recurring series; see [Recurring events](/docs/product/recurring-events). |
| Reminders | Default reminders, or per-event offsets; see [Notifications](/docs/product/notifications). |
| Attendees | Invite people and manage RSVP; see [Event invites and RSVP](/docs/product/event-invites-and-rsvp). |

Events also carry a **location** and a **status** (`confirmed`, `tentative`,
`cancelled`) in the API and data model. The current web dialog has no location
field, so location is only used by integrations.

### Create an event

- **Drag** on the timed grid to draw a block (minimum 30 minutes), then fill in
  the dialog.
- **Right-click** an empty slot and choose **New event**, or use **New all-day
  event** on an all-day cell.
- Click an existing event to view or edit it.

### Time zones

Kalendee keeps times unambiguous by storing event instants in UTC while showing
them in a zone:

- Your **account time zone** is the default used when you sign in.
- The **calendar time zone** is the fallback for events on that calendar.
- An event may have its own **time zone**.
- The week view always renders in your **browser** time zone, and adds a `tz`
  query parameter to the URL so a copied link shows the same wall-clock times
  for the person who opens it.

Change your account time zone under **Settings → My Account**. Changing it does
not move events; it changes how they are displayed.

### All-day and multi-day events

- An all-day event has a start date and an end date. The end date is
  **inclusive** in the dialog: an event from the 10th to the 12th covers three
  days.
- Internally the event ends at midnight on the day after the last day, which is
  how it stays correct across time zones.
- Multi-day timed events work the same way as single-day ones; just set an end
  date that is later than the start.

### Edit and move events

- Click an event and change its fields, then select **Save**.
- **Drag** a non-recurring event to move it, or drag its bottom edge to change
  its duration.
- Right-click an event and choose **Move to calendar** to move it to another
  calendar you can write to.
- Recurring events cannot be dragged on the grid; open them and edit the series
  instead.

Saving uses the event's `etag` as an optimistic-concurrency token. If someone
else changed the event first, the save is rejected rather than silently
overwriting their change. Reload the dialog and reapply your edit.

### Delete an event

Open the event and select **Delete**, or right-click it and choose **Delete**.
Deleting a recurring event deletes the series.

## Search

There is no full-text event search in the web UI yet. To find something, use the
**Day / Week / Month** views with **Previous / Today / Next**, or jump by editing
the `date` in the URL. Search is a natural candidate for the planned Compose
clients and the CalDAV library rather than a one-off web feature.

## Related

- [Recurring events](/docs/product/recurring-events)
- [Sharing and following](/docs/product/sharing-and-following)
- [Availability and scheduling](/docs/product/availability-and-scheduling)
- [Theming](/docs/product/theming)
