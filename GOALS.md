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

- Read-only Discord server event import ships first; linking Google, Microsoft,
  Apple, CalDAV, and ICS calendar accounts and mirroring them as regular
  Kalendee calendars is tracked in the
  [external calendar connections guide](https://kalendee-docs.pages.dev/docs/product/external-calendars).

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
  follower/following activity, over SMTP or a Cloudflare Email Routing Worker
  (`workers/mailer`); when mail is disabled, messages are logged instead.
- Configurable native notifications for upcoming events: any number of
  reminders, each with any offset before the event, plus a toggleable at-start
  notification.
- Browser notifications first, with native notifications on Android, iOS, and
  Desktop later.

## Media storage

- Object storage for user avatars and profile pictures: a local filesystem
  backend by default, or any S3-compatible service (including Cloudflare R2)
  through the S3 API.
- By default media is proxied through the server, so clients and the web UI
  never hold storage credentials and the bucket can stay private. Optionally,
  `storage.publicBaseUrl` redirects reads at a CDN/R2 edge gateway so image
  traffic bypasses the app server.

## Documentation and hosting

- Product docs and self-hosting guides are served from an Astro site built out
  of `docs/` and deployed to Cloudflare Pages.
- Two optional Cloudflare Workers extend a deployment at the edge: a mailer for
  the `cloudflare` mail provider and an R2 read gateway for object storage.

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
