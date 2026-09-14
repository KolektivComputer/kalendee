---
title: Recurring events
description: Create repeating events and understand how series are edited and split.
---

Any event can be turned into a recurring series. Kalendee supports the common
recurrence patterns and is explicit about what it does not support yet.

## Create a repeating event

1. Create or open an event.
2. Turn on **Repeats**.
3. Set **Every** and the unit — days, weeks, months, or years.
4. Optionally set **Until** (a date) or **Count** (a number of occurrences),
   or both.
5. Select **Create** or **Save**.

The dialog notes that edits apply to the **whole series**, and that times are
stored in UTC.

## What recurrence supports

| Setting | Range | Notes |
| --- | --- | --- |
| Frequency | `DAILY`, `WEEKLY`, `MONTHLY`, `YEARLY` | Weekly repeats happen on the same weekday as the series start. |
| Interval | 1–99 | "Every 2 weeks", "every 3 days", and so on. |
| Until | Date/time after the start | The series stops at or after this point. |
| Count | 1–999 | The maximum number of occurrences. |

If you set both **Until** and **Count**, the series stops at whichever limit is
reached first.

Recurrence advances in **local time** in the event's time zone and keeps the
same wall-clock time, so a 09:00 weekly meeting stays at 09:00 across daylight
saving changes.

## What recurrence does not support

The current engine only stores frequency, interval, an end date, and a count. It
does **not** support:

- Selecting specific weekdays or week-of-month positions (`BYDAY`,
  `BYSETPOS`).
- Excluding or adding individual dates (`EXDATE`, `RDATE`).
- Per-occurrence overrides ("this occurrence only", "edit one instance").
- Round-tripping arbitrary external `RRULE` strings.

A full recurrence engine is a prerequisite for honest two-way external calendar
sync and is called out in
[External calendars](/docs/product/external-calendars); the broader direction is
in [GOALS.md](https://github.com/KolektivComputer/kalendee/blob/main/GOALS.md).

## How occurrences are generated

- Occurrences are expanded for the visible range when the grid loads. Each
  occurrence is the same event shifted forward by the recurrence rule.
- The expansion stops at the series **until** or **count**, and has a safety
  cap so a bad rule cannot load forever.
- Because an occurrence is not a separate stored event, it has no independent
  identity in the database.

## Edit or delete a series

- Open a repeating event and change its fields. The change applies to the
  **entire series**, past and future occurrences included.
- **Delete** removes the whole series.
- Repeating events cannot be dragged or resized on the grid. Open the event
  instead.

## Split a series across calendars

The one series operation that does more than edit the master is moving a
recurring event to another calendar:

1. Right-click a repetition in the grid.
2. Choose **Move to calendar** (shown as **Move this and following** for a
   recurring event).
3. Pick the destination calendar.

Kalendee splits the series at the occurrence you selected:

- Occurrences **before** the selected one stay on the original calendar.
- The selected occurrence **and everything after** move to the destination
  calendar as a new series.

This is a split, not a per-occurrence override: it produces two independent
series, and later edits to one do not affect the other.

## Related

- [Calendars and events](/docs/product/calendars-and-events)
- [External calendars](/docs/product/external-calendars)
- [Event invites and RSVP](/docs/product/event-invites-and-rsvp)
- [Notifications](/docs/product/notifications)
