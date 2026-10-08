---
title: Clients
description: The web UI, the state of the Compose clients, and the planned CalDAV library.
---

Kalendee is designed around multiple first-party clients sharing one server.
Today, the web UI is the complete client, and the Compose mobile/desktop apps
are usable previews. This page is explicit about what you can use right now.

## Client status

| Client | Status | How to use it |
| --- | --- | --- |
| **Web UI** | Complete | Open the server URL in any modern browser. |
| **Android** (Compose) | Preview | Build and install the debug APK; shared day/week/month calendar with drawer navigation, multi-server accounts, and local reminders. |
| **iOS** (Compose + Swift entry) | Preview | Sideload the unsigned CI IPA with SideStore/AltStore; same shared UI, local notifications only. |
| **Desktop** (Compose, JVM) | Preview | `./gradlew :app:desktopApp:run`; same shared UI, no OS notifications. |
| **Third-party CalDAV clients** | Not supported | No CalDAV endpoint exists yet. |

## The web UI

The web UI is the product to use. The server serves it at `/`, and it implements
the full feature set described in these docs: calendars and events, recurrence,
sharing, public links, organizations, scheduling, invites, and notifications.

- It is built with Svelte on a daisyUI theme.
- The server owns the URLs, page ids, and payload types; the web pack implements
  page ids and does not fetch `/api/v1` directly for page data.
- Theming is per user; see [Theming](/docs/product/theming).
- Because it is a normal web page, it works on phones and tablets through the
  browser, including the sidebar's drawer layout on small screens. There is no
  native app experience, offline mode, or OS-level notification delivery yet.

## The Compose clients

The Android, iOS, and Desktop targets build the same Compose Multiplatform UI
from `:app:shared`. It is built on a foundation-only daisyUI-style design
system (`ui/design`, oklch brand tokens) with vendored Lucide icons
(`ui/icons`) — no Material components or Material theme. The UI is a themeable
calendar with swipeable day, week, and month pages (the month grid shows `+N`
overflow), an event editor and detail view, multiple server accounts,
login/register, settings, and an Upcoming list. A responsive shell shows a
drawer with calendars, organizations, and friends; organizations are read-only,
while friendships can be searched, requested, accepted/declined, and removed.
Shared non-UI logic (JSON API client for `/api/v1`, cookie
sessions, server registry, calendar models) lives in `:core`.

They are usable previews, not complete clients. The honest gaps: no CalDAV, no
push notifications, and no offline mode. Reminders are scheduled locally only —
Android uses exact alarms and re-arms on boot, iOS uses
`UNUserNotificationCenter`, and Desktop has no OS notification integration (the
scheduler is a no-op). Android debug APKs come from
`./gradlew :app:androidApp:assembleDebug`; unsigned iOS IPAs come from the
`iOS (unsigned IPA)` GitHub workflow for SideStore/AltStore sideloading.

The intended end state is a shared Compose Multiplatform UI across all three,
themeable alongside the web UI, with shared non-UI logic in the `:core` module.
That direction is recorded in
[GOALS.md](https://github.com/KolektivComputer/kalendee/blob/main/GOALS.md).

## CalDAV

Kalendee's goal is to be a CalDAV server, but the CalDAV protocol is **not
implemented yet**. You cannot point Apple Calendar, Thunderbird, Evolution, or
another CalDAV client at a Kalendee instance today.

The plan is to implement CalDAV (RFC 4791) and `iCalendar` in a dedicated,
pure multiplatform Kotlin library that is separate from the server and the
clients, then build the server's protocol support on top of it. This is why the
repository treats the protocol as a standalone deliverable rather than a server
feature; see
[GOALS.md](https://github.com/KolektivComputer/kalendee/blob/main/GOALS.md).

In the meantime, the web UI and the first-party Compose clients are the only
ways to read and write calendars.

## Related

- [Overview](/docs/product/index)
- [Theming](/docs/product/theming)
- [External calendars](/docs/product/external-calendars)
- [Accounts and security](/docs/product/accounts-and-security)
