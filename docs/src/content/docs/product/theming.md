---
title: Theming
description: Accent colors, the web UI theme, and how theming is shared across Kalendee.
---

Kalendee is meant to be themeable end to end. Today the user-facing control is
the **accent color**, which is stored on your account and applied everywhere the
web UI uses that color.

## Choose an accent

1. Open **Settings → Appearance**.
2. Pick a color: Primary, Secondary, Accent, Info, Success, or Error.

The choice saves immediately and applies before the next page load. It changes
the primary color used by buttons, the "today" highlight, links, and other
accents. It is per user, not per instance or per calendar.

## Calendar colors

Calendars also carry a color from the same palette — primary, secondary, accent,
info, success, warning, or error — chosen when you create or edit a calendar.
Calendar colors use the theme's CSS variables, so they follow the active theme
rather than being hard-coded hex values. The event blocks on the grid are filled
with the calendar's color.

## The web UI theme

The web UI ships the **`kalendee`** daisyUI theme: a dark theme with the project
palette (orange primary, blue secondary, magenta accent). The theme is defined
by the web pack, and per-user accent selection overrides the primary color at
runtime.

This is what "themeable web UI" means in practice:

- The server serves the page shell, and the bundled web app provides the styling
  and the page content.
- Because styling is daisyUI-based, the same palette names (`primary`,
  `secondary`, `accent`, `info`, `success`, `warning`, `error`) are used for
  accents, calendar colors, and UI accents throughout.

## The shared Kolektiv theme system

Kalendee is a Kolektiv project, and its palette is shared with the broader
Kolektiv tooling. The documentation site you are reading, for example, uses the
shared `@kolektiv/common-docs-chrome` and theme packages with a
`kalendee-dark` / `kalendee-light` pair that mirrors the web app's palette. The
intent is one visual language across the server's web UI, the docs, and the
native clients.

Full theme selection (including light/dark switching) is not exposed as a user
setting yet. The long-term goal is for the Compose clients and the web UI to
share a theme that users can change, described in
[GOALS.md](https://github.com/KolektivComputer/kalendee/blob/main/GOALS.md).

## Related

- [Calendars and events](/docs/product/calendars-and-events)
- [Clients](/docs/product/clients)
- [Directory and profiles](/docs/product/directory-and-profiles)
- [Overview](/docs/product/index)
