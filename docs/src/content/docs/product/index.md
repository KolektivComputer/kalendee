---
title: Overview
description: What Kalendee is, who it is for, and what the web app does today.
---

Kalendee is a self-hosted calendar application written in Kotlin Multiplatform.
It is made of a Ktor server, a themeable web UI built with the
[Keel](https://github.com/lizainslie/keel) framework, and a set of work-in-progress
Compose Multiplatform clients. When you run Kalendee you own the server, the
database, and the events on it.

## What Kalendee is

The project has three long-term pieces:

- A **self-hosted CalDAV server** with first-party clients for Android, iOS, and
  Desktop, in the spirit of Notion Calendar / Cron.
- A **themeable web UI** that runs in any browser and talks to the same server.
- A **pure multiplatform Kotlin CalDAV library** implementing the protocol
  separately from the server and clients.

The server is real: it stores calendars, events, shares, organizations, invites,
and notifications in PostgreSQL and exposes them through a JSON API at
`/api/v1`. The web UI consumes that API and is the client to use today.

## Who it is for

Kalendee is aimed at people and small groups who would rather run their own
calendar than rent one:

- Individuals who want their week view, sharing, and scheduling on their own
  infrastructure.
- Small teams and clubs that need shared calendars, organizations, and
  availability without a SaaS account.
- Self-hosters who are comfortable running a server and PostgreSQL.

It is not a drop-in replacement for a hosted calendar service yet. See
[What works today](#what-works-today) for the honest list.

## The self-hosted CalDAV pitch

"Self-hosted CalDAV" describes where the project is going. The web UI today
speaks the server's JSON API, not CalDAV. The CalDAV protocol (RFC 4791 and
`iCalendar`) is planned as a **separate pure-KMP library** and is not
implemented in this repository yet. That means you cannot point Apple Calendar,
Thunderbird, or another CalDAV client at a Kalendee instance today. The intended
shape — a self-hosted server, first-party clients, and a reusable protocol
library — is described in
[GOALS.md](https://github.com/KolektivComputer/kalendee/blob/main/GOALS.md).

## What works today

The following is implemented in the web UI and server:

| Area | What you get | Guide |
| --- | --- | --- |
| Accounts | Registration (configurable), sign-in, sessions, optional/soft/required email verification, avatars | [Getting started](/docs/product/getting-started), [Accounts and security](/docs/product/accounts-and-security) |
| Calendars | Personal calendars with color and time zone; hide/show; delete | [Calendars and events](/docs/product/calendars-and-events) |
| Events | Day/week/month views, timed and all-day events, notes, links, drag to create/move/resize, reminders | [Calendars and events](/docs/product/calendars-and-events) |
| Recurrence | Daily/weekly/monthly/yearly series with interval, end date, or occurrence count | [Recurring events](/docs/product/recurring-events) |
| Sharing | Per-user read/write shares and friends | [Sharing and following](/docs/product/sharing-and-following) |
| Public access | Revocable read-only calendar links, public pages, RSS feeds, per-user and per-calendar visibility | [Public access](/docs/product/public-access) |
| Organizations | Members, invitations, `owner`/`admin`/`member` roles, teams and team calendar grants | [Organizations](/docs/product/organizations) |
| Groups and quotas | Admin-managed groups with storage quotas | [Groups and quotas](/docs/product/groups-and-quotas) |
| Scheduling | Office hours, slot requests, accept/decline that creates a meeting | [Availability and scheduling](/docs/product/availability-and-scheduling) |
| Invites and RSVP | Invite users or email addresses, yes/no/maybe, open RSVP links | [Event invites and RSVP](/docs/product/event-invites-and-rsvp) |
| Notifications | In-app notifications with an unread badge, plus browser reminders while the app is open | [Notifications](/docs/product/notifications) |
| External calendars | Read-only Discord server event import | [External calendars](/docs/product/external-calendars) |
| Directory and profiles | Public directory, `/u/{username}` profiles | [Directory and profiles](/docs/product/directory-and-profiles) |
| Theming | Per-user accent color on a daisyUI theme | [Theming](/docs/product/theming) |

The web UI is the only complete client. The Android, iOS, and Desktop Compose
apps are still the template scaffold; see [Clients](/docs/product/clients).

## What is planned

The following are goals, not shipped features. Each is tracked in
[GOALS.md](https://github.com/KolektivComputer/kalendee/blob/main/GOALS.md):

- The CalDAV protocol library and real protocol endpoints.
- The Compose clients (Android, iOS, Desktop) beyond the current scaffold.
- External calendar providers beyond Discord: Google, Microsoft, Apple iCloud,
  generic CalDAV, and ICS subscriptions.
- Native (OS-level) notifications instead of browser notifications only.
- A federated social direction: following users as well as calendars.
- Optional per-calendar RSS toggles (an RSS feed already exists for every
  public calendar).

## How these docs are organized

- **Product** (this section) is written for people using or evaluating
  Kalendee. It covers the web UI feature by feature.
- **Self-hosting** covers deployment and configuration. It is documented
  separately because it is only relevant once you run your own server.
- **Referenced design documents** such as
  [External calendars](/docs/product/external-calendars) describe planned
  architecture as well as what shipped, and say which is which.

## Related

- [Getting started](/docs/product/getting-started)
- [Calendars and events](/docs/product/calendars-and-events)
- [Clients](/docs/product/clients)
- [Accounts and security](/docs/product/accounts-and-security)
