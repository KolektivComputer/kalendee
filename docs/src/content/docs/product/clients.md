---
title: Clients
description: The web UI, the state of the Compose clients, and the planned CalDAV library.
---

Kalendee is designed around multiple first-party clients sharing one server.
Today, one client is complete: the web UI. This page is explicit about what you
can use right now.

## Client status

| Client | Status | How to use it |
| --- | --- | --- |
| **Web UI** (Keel pack) | Complete | Open the server URL in any modern browser. |
| **Android** (Compose) | Scaffold only | Not a usable calendar yet. |
| **iOS** (Compose + Swift entry) | Scaffold only | Not a usable calendar yet. |
| **Desktop** (Compose, JVM) | Scaffold only | Not a usable calendar yet. |
| **Third-party CalDAV clients** | Not supported | No CalDAV endpoint exists yet. |

## The web UI

The web UI is the product to use. The server serves it at `/`, and it implements
the full feature set described in these docs: calendars and events, recurrence,
sharing, public links, organizations, scheduling, invites, and notifications.

- It is built with the [Keel](https://github.com/lizainslie/keel) framework and
  Svelte, on a daisyUI theme.
- The server owns the URLs, page ids, and payload types; the web pack implements
  page ids and does not fetch `/api/v1` directly for page data.
- Theming is per user; see [Theming](/docs/product/theming).
- Because it is a normal web page, it works on phones and tablets through the
  browser, including the sidebar's drawer layout on small screens. There is no
  native app experience, offline mode, or OS-level notification delivery yet.

## The Compose clients

The Android, iOS, and Desktop targets exist and build, but the shared Compose UI
is still the JetBrains KMP template: a greeting screen and a platform name.
They are not a calendar client yet and should not be evaluated as one.

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

In the meantime, the web UI is the only supported way to read and write
calendars.

## Related

- [Overview](/docs/product/index)
- [Theming](/docs/product/theming)
- [External calendars](/docs/product/external-calendars)
- [Accounts and security](/docs/product/accounts-and-security)
