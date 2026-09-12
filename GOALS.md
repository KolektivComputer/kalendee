# Kalendee Goals

Kalendee is a Kotlin Multiplatform CalDAV server and client. This document
captures where the project is heading beyond the current scaffold — the shape
features should take and how they fit together. It is not a schedule. Product
intent lives in [README.md](./README.md), the module map and commands in
[DEVELOPING.md](./DEVELOPING.md), and implementation rules for agents in
[AGENTS.md](./AGENTS.md).

## Self-hosted server and first-party clients

- A self-hosted CalDAV server with first-party Kotlin Multiplatform clients for
  Android, iOS, and Desktop, in the spirit of Notion Calendar / Cron.
- The Compose Multiplatform UI is shared across clients through `:app:shared`;
  shared non-UI logic lives in `:core`.
- A themeable Keel web UI serves the same product in a browser. `:server` owns
  the page ids and payload types (`dev.kolektiv.kalendee.web`); the Keel pack
  implements those ids and does not fetch `/api/v1` for page data.
- The CalDAV protocol is implemented as a separate pure-KMP library, kept out
  of `:server` and `:app:shared`.

## External calendars

- Linking Google, Microsoft, Apple, CalDAV, and ICS calendar accounts and
  mirroring them as regular Kalendee calendars is tracked in
  [docs/external-calendars.md](./docs/external-calendars.md).

## Theming

- Clients and the web UI are themeable end to end. The Compose clients share a
  theme, and the Keel pack uses daisyUI themes so the web UI matches.
- Themes are a user-facing preference, not a fixed look.

## Calendar sharing

- Revocable public read-only links for calendars.
- Invitations to other users on the same instance, with view or edit
  permissions.
- A public calendar page that can be opened without an account where the owner
  allows it.

## Social and federation

- A federated social direction: users can follow other users and their
  calendars, with followers and following lists.
- Calendars can be publicly followable.
- Each public calendar can expose an optional RSS feed.

## Scheduling and RSVP

- Meeting scheduling proposals: people can propose times against a linked
  calendar, with configurable anonymous vs logged-in access.
- Event RSVP with invitee responses of yes, no, or maybe.
- Sharing and following are the foundation: proposals and RSVPs build on the
  same permissions and audience model.

## Notifications

- Email for account verification, security alerts, calendar invites, and
  follower/following activity.
- Configurable native notifications for upcoming events: any number of
  reminders, each with any offset before the event, plus a toggleable at-start
  notification.
- Browser notifications first, with native notifications on Android, iOS, and
  Desktop later.

## Media storage

- S3-compatible object storage for user avatars and profile pictures.
- Media is proxied through the server, so clients and the web UI never hold
  storage credentials and the bucket can stay private.

## Configuration

- Ktor `EngineMain` with HOCON `application.conf` as the single source of truth
  for server configuration.
- Environment variables appear only as `${?VAR}` substitutions for secrets;
  they do not form a parallel configuration surface.

## Security

- Verified email addresses.
- Session hardening.
- Security alerts that stay audit-friendly: verification, session and account
  events, and sharing changes are surfaced clearly.
