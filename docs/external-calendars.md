# External calendar connections

Decision document for linking external calendar accounts (Google, Apple,
Microsoft, generic CalDAV, ICS) and mirroring them into Kalendee. Derived from
a read-only architecture report; no implementation exists yet.

## Contents

- [Goal](#goal)
- [Guiding architecture principle](#guiding-architecture-principle)
- [Provider matrix](#provider-matrix)
- [Recommended implementation order](#recommended-implementation-order)
- [Milestone plan](#milestone-plan)
- [Security summary](#security-summary)
- [Data model sketch](#data-model-sketch)
- [How to help / what to decide](#how-to-help--what-to-decide)

## Goal

Let a Kalendee user link calendar accounts they already have and see those
calendars inside Kalendee as if they were created there. Mirrored calendars
must fully participate in the product: sharing, following, public links, RSS,
RSVP and invites, availability and time-slot requests, reminders, and
notifications. When two-way sync is enabled, edits made in Kalendee write back
to the provider.

## Guiding architecture principle

**A mirrored external calendar is a regular `calendars` row owned by the
linking user; mirrored events are regular `events` rows in that calendar.**
External identity is additive: connection/mapping tables plus a few `events`
columns.

Nothing about permissions, shares, followers, public links, RSS, RSVP,
availability, reminders, or notifications needs a second code path, because
they all key off `calendars.id` and `events.id` (see
[Models.kt](../core/src/commonMain/kotlin/dev/kolektiv/kalendee/calendar/Models.kt),
[RssRoutes.kt](../server/src/main/kotlin/dev/kolektiv/kalendee/api/RssRoutes.kt),
[ReminderService.kt](../server/src/main/kotlin/dev/kolektiv/kalendee/reminders/ReminderService.kt)).
The only new logic is (a) where mirrored rows come from and (b) routing writes
through the connection instead of only to Postgres. Read-only mirrors are
ordinary rows flagged provider-managed, so the store and UI reject local edits.

## Provider matrix

Effort is per adapter, excluding shared OAuth/vault infrastructure:
**S** = under 1 week, **M** = 1–3 weeks, **L** = over 3 weeks. "Push" means
provider-initiated change notification; everything else polls.

### Linkable accounts

| Provider | Auth mechanism | Calendar API | Two-way | Push / webhooks | Effort | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| Google Calendar | OAuth2 offline refresh token | Calendar API v3 | Yes | `events.watch` channels | M | Incremental `syncToken`; full RRULE + EXDATE; sensitive scopes require Google app verification for public deployments. |
| Microsoft Outlook / O365 | OAuth2 + PKCE (MSAL) | Graph v1.0 | Yes | Graph change notifications | M | Multi-tenant + personal accounts; `calendarView/delta`; subscriptions last about 3 days and need renewal. |
| Apple iCloud | CalDAV + app-specific password | CalDAV (RFC 4791) | Yes | None (poll) | S–M | **Sign in with Apple does not grant calendar access.** 2FA users must create an app-specific password. Reuses the generic CalDAV adapter. |
| Generic CalDAV | Basic auth / app password; Nextcloud can do OAuth | CalDAV | Yes | None generally (poll) | S–M once the adapter exists | Nextcloud, Fastmail, mailbox.org, Posteo, GMX, Yandex, Zoho, Synology, Radicale, Baikal, SOGo, Stalwart, DAViCal, Xandikos. Discovery via `/.well-known/caldav`; `sync-collection` with ctag fallback. |
| Fastmail (JMAP) | OAuth2 or app password | JMAP Calendars (RFC 8984), also CalDAV | Yes | JMAP push via EventSource | M | CalDAV is the cheap path; JMAP is the modern path with granular push. |
| Zoho Calendar | OAuth2 | Zoho Calendar API and CalDAV | Yes | Limited (poll) | M | Both an API and a CalDAV path. |
| Yandex Calendar | OAuth2 or app password | CalDAV | Yes | None (poll) | S–M | App passwords for 2FA; region/availability considerations. |
| Feishu / Lark | OAuth2 | Calendar Open API v4 | Yes | Event subscription webhooks | M–L | Open-platform app review; `feishu.cn` vs `larksuite.com` endpoints. |
| Tencent WeCom / DingTalk | OAuth2 | Proprietary calendar APIs | Yes | Webhooks | M–L | Region-locked; out of initial scope. |
| ICS subscription URL | None (secret URL) | ICS / Webcal | No (read-only) | HTTP `ETag` / `Last-Modified` polling | S | Cheap catch-all for any "publish calendar" URL, including the providers below. |

### No public calendar API (ICS fallback only)

| Provider | Access today | Recommendation |
| --- | --- | --- |
| Proton Calendar | ICS export / shared links only (E2E encrypted) | Offer as an ICS subscription; not linkable. |
| Tutanota (Tuta) | No calendar API; ICS export where available | ICS subscription if a stable URL exists, otherwise not feasible. |
| HEY Calendar | No public API | Not feasible. |
| Yahoo Calendar | Legacy CalDAV retired; ICS export | ICS subscription. |
| AOL Calendar | Legacy CalDAV retired; ICS export | ICS subscription. |
| Samsung Calendar | Private Samsung account sync | Not feasible. |
| Naver Calendar | ICS export/share only | ICS subscription. |
| Notion Calendar | Client app; Notion has no calendar API | Not feasible. |

## Recommended implementation order

1. **Google Calendar** — largest user base, mature incremental sync
   (`syncToken`), offline refresh tokens, and push channels. Exercises every
   hard part of the design first: vault, refresh, delta, two-way, recurrence.
2. **Microsoft Graph** — second-largest base and the enterprise requirement.
   Same OAuth framework as Google with different tenant/token details; delta
   queries and subscriptions are well documented.
3. **Generic CalDAV** — one adapter unlocks **Apple iCloud** plus Nextcloud,
   Fastmail, mailbox.org, Posteo, GMX, Yandex, Zoho, Synology, Radicale,
   Baikal, SOGo, Stalwart, DAViCal, and Xandikos. Apple makes this
   non-optional: iCloud has no usable OAuth calendar API, so CalDAV is the
   only path. Aligns with the pure-KMP CalDAV library direction in
   [GOALS.md](../GOALS.md) and [AGENTS.md](../AGENTS.md).
4. **ICS subscriptions** — read-only, no OAuth, small effort. Covers Proton,
   Tutanota, HEY, Yahoo, AOL, Samsung, Naver, and any `webcal://` link while
   the API integrations mature.

## Milestone plan

| Milestone | Scope | Effort |
| --- | --- | --- |
| M1 | Connection + read-only mirror (Google + Graph) | 4–6 weeks |
| M2 | Two-way writes + periodic sync + status | 4–6 weeks |
| M3 | Generic CalDAV + push/webhooks | 3–5 weeks |
| M4 | Social/notifications parity + `oauthRegistration` gating | 2–3 weeks |

Estimates assume one senior engineer familiar with the codebase; provider
app-review lead time is additional.

### M1 — Connection + read-only mirror (Google + Graph) — 4–6 weeks

- V17 migration (see [data model sketch](#data-model-sketch)).
- Token vault (AES-256-GCM), HTTP client, provider registry.
- Google and Microsoft OAuth start/callback, token refresh, account identity,
  normalized-email matching.
- Pull sync: full then incremental (`syncToken` / `deltaLink`), mapping remote
  calendars to `calendars` rows and remote events to `events` rows; mirror
  calendars marked read-only in DTOs and UI.
- Settings "Connected Accounts" tab: connect/disconnect, calendar selection,
  last sync/error.
- **Risks:** RRULE fidelity (master-only fallback), Google verification (BYO
  credentials), delta paging mistakes (persist the token only after a page is
  fully applied), timezone handling, local `etag` vs provider ETag semantics.

### M2 — Two-way writes + periodic sync + status — 4–6 weeks

- Write-through create/update/delete with provider `If-Match`; provider result
  written back to `external_etag` / `external_updated_at`, local `etag` bumped
  so existing client concurrency checks keep working.
- Tombstone upload and remote-delete application; conflict policy preserves
  the local edit as a "(conflict)" copy and notifies.
- `SyncService` loop with exponential backoff and jitter, per-connection
  mutex, manual "Sync now", status surfaced in settings and sidebar.
- Block cross-provider `moveEvent` and unsupported recurrence edits.
- **Risks:** conflict UX, write amplification on drag operations, recurrence
  exceptions, provider 400s on malformed RRULE, notification spam (notify on
  state change only).

### M3 — Generic CalDAV + push/webhooks — 3–5 weeks

- CalDAV adapter: discovery, `PROPFIND` / `REPORT`, `sync-collection` with
  ctag fallback, `If-Match`; iCloud app-specific password connect flow; covers
  all generic CalDAV servers.
- Google `events.watch` and Graph subscriptions with renewal, validation
  handshake, and a public webhook route; push triggers an incremental sync.
- Optional: ICS subscription provider (read-only).
- **Risks:** CalDAV server quirks (sync-token support, href encoding, auth
  schemes), public HTTPS requirement for webhooks, subscription renewal bugs,
  channel-token replay.

### M4 — Social/notifications parity + registration gating — 2–3 weeks

- `oauthRegistration` setting (new `app_settings` key + admin toggle,
  following the existing `registration` precedent in
  [AuthService.kt](../server/src/main/kotlin/dev/kolektiv/kalendee/auth/AuthService.kt));
  login/register pages show provider buttons only when allowed.
- Verify mirrored calendars across share/follow/RSS/availability/reminders/
  RSVP; provider badges and one-shot error notifications; docs.
- Optional: attendee lists and reminder write-back, richer recurrence.
- **Risks:** identity edge cases (unverified email, duplicate usernames,
  first-user admin), account-deletion cascade, provider-specific RSVP loops.

### Cross-cutting risks

- **Google scope verification.** `calendar.readonly` / `calendar.events` are
  sensitive; public multi-user use needs OAuth app verification (privacy
  policy, domain verification, demo video) and can take weeks. Per-instance
  BYO credentials are the mitigation, not an afterthought.
- **Recurrence fidelity.** V17's four recurrence columns cannot round-trip
  provider RRULEs. Flag/truncate explicitly in M1 and do not claim two-way
  recurrence before a real RRULE engine exists.
- **Token vault key management.** Losing `KALENDEE_SECRET_KEY` means every
  connection needs re-authentication. No plaintext fallback; ship key
  versions and rotation from day one.
- **Rate limits and retries.** Sync per connection must be serialized and
  must honor `Retry-After` / provider backoff, or a naive loop will trigger
  throttling and spurious `needs_reauth` states.
- **Conflict handling.** Two-way sync is inherently conflict-prone; shipping
  read-only M1 first de-risks it and still delivers value.

## Security summary

- **Token vault:** AES-256-GCM with a unique 12-byte nonce per sealed value.
  Keys come from `KALENDEE_SECRET_KEY` (base64, 32 bytes) and carry integer
  key versions (`KALENDEE_SECRET_KEYS`) so tokens can be rotated. Fail closed
  when the key is missing; never store or log plaintext tokens.
- **OAuth flow:** PKCE S256 for every provider. Single-use random `state`
  stored server-side (`oauth_states`, 10-minute TTL, bound to the signed-in
  user, or to a `SameSite=Lax` `HttpOnly` cookie for the registration path).
  The verifier is stored encrypted and never appears in a URL.
- **Redirects:** the exact registered URI
  `{baseUrl}/api/v1/oauth/{provider}/callback`; never accept a `redirect_uri`
  from the request; only relative return paths, to prevent open redirects.
- **Scopes:** least privilege. Request read-only scopes when the user picks a
  read-only mirror, write scopes only for two-way. Never request Gmail, Drive,
  or contacts.
- **Credentials:** per-instance BYO OAuth client credentials
  (`KALENDEE_GOOGLE_CLIENT_ID` / `_SECRET`,
  `KALENDEE_MICROSOFT_CLIENT_ID` / `_SECRET`) via HOCON `${?VAR}`, matching
  the configuration rules in [GOALS.md](../GOALS.md). This avoids one shared
  app's verification, quota, and liability.
- **Secrets in logs:** tokens, authorization codes, PKCE verifiers, and
  `Authorization` headers are never logged; error responses stay generic.
- **Disconnect/revoke:** revoke at the provider where supported (Google),
  delete the connection and vaulted tokens, then either delete mirror
  calendars or detach mirrored events, per the user's choice. Background sync
  stops and status rows are cleaned up.
- **Webhook authenticity:** Google channel token and resource-state headers;
  Graph `clientState` plus `validationToken` handshake; idempotent processing
  with replay windows.

## Data model sketch

Proposed as migration `V17__external_calendars.sql`. The latest migration in
the tree is
[V16__organizations.sql](../server/src/main/resources/db/migration/V16__organizations.sql).
Draft only; table/column names can change during implementation.

```sql
-- One credential set per user per provider account.
CREATE TABLE calendar_connections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider TEXT NOT NULL,                 -- google | microsoft | caldav | ics
    external_account_id TEXT NOT NULL,      -- stable provider account/subject id
    account_email TEXT,
    display_name TEXT,
    access_token_ciphertext BYTEA,
    access_token_nonce BYTEA,
    refresh_token_ciphertext BYTEA,
    refresh_token_nonce BYTEA,
    token_key_version INTEGER NOT NULL DEFAULT 1,
    token_expires_at TIMESTAMPTZ,
    scopes TEXT,
    status TEXT NOT NULL DEFAULT 'active',  -- active | needs_reauth | disabled
    last_sync_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, provider, external_account_id)
);

-- Remote collection mapped to exactly one local calendar.
CREATE TABLE external_calendars (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES calendar_connections(id) ON DELETE CASCADE,
    external_id TEXT NOT NULL,              -- Google/Graph id or CalDAV href
    calendar_id UUID NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
    external_name TEXT,
    sync_direction TEXT NOT NULL DEFAULT 'pull',  -- pull | push | both
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sync_token TEXT,                        -- syncToken / deltaLink / CalDAV sync-token
    last_sync_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (connection_id, external_id),
    UNIQUE (calendar_id)
);

-- Single-use OAuth state plus encrypted PKCE verifier.
CREATE TABLE oauth_states (
    state TEXT PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,  -- NULL for registration flow
    provider TEXT NOT NULL,
    code_verifier_ciphertext BYTEA NOT NULL,
    code_verifier_nonce BYTEA NOT NULL,
    redirect_uri TEXT NOT NULL,
    return_to TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);

-- Local deletes waiting to be pushed to the provider.
CREATE TABLE external_event_tombstones (
    id UUID PRIMARY KEY,
    external_calendar_id UUID NOT NULL REFERENCES external_calendars(id) ON DELETE CASCADE,
    external_uid TEXT NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL,
    uploaded_at TIMESTAMPTZ,
    UNIQUE (external_calendar_id, external_uid)
);

-- External identity on the otherwise-normal event row.
ALTER TABLE events
    ADD COLUMN external_calendar_id UUID REFERENCES external_calendars(id) ON DELETE SET NULL,
    ADD COLUMN external_uid TEXT,
    ADD COLUMN external_etag TEXT,
    ADD COLUMN external_updated_at TIMESTAMPTZ;
CREATE UNIQUE INDEX events_external_uid_key ON events (external_calendar_id, external_uid);

-- Case-insensitive verified-email matching for OAuth identity linking.
ALTER TABLE users ADD COLUMN email_normalized TEXT;
UPDATE users SET email_normalized = lower(email) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX users_email_normalized_key
    ON users (email_normalized) WHERE email_normalized IS NOT NULL;
```

Notes:

- `calendars` and `events` stay the only read path, so every social and
  scheduling feature keeps working without a second store.
- `events.etag` remains the local optimistic-concurrency token; the provider's
  ETag/`If-Match` value lives in `external_etag`.
- Disconnect uses `ON DELETE SET NULL` so mirrored events can be kept as
  frozen local copies, or the whole mirror calendar is deleted if the user
  prefers.
- **Recurrence is master-only best effort.** The current model stores only
  frequency/interval/until/count
  ([Recurrence.kt](../core/src/commonMain/kotlin/dev/kolektiv/kalendee/calendar/Recurrence.kt)),
  so M1 imports the master with simple rules and falls back to the master's
  start/end plus a "truncated" flag for anything with `BYDAY`/`BYSETPOS`/
  `EXDATE`/`RDATE`/per-instance overrides. Several providers require EXDATE
  and overrides for fidelity, so a real RRULE engine is a prerequisite for
  honest two-way recurrence.
- There is no attachment schema today; external attachments are out of scope
  until one exists.
- Applying remote changes must not bump `events.updated_at`, or every pulled
  event will look locally modified during conflict detection; compare against
  `external_updated_at`.

## How to help / what to decide

Product decisions needed (recommended defaults in parentheses):

- **Which providers to prioritize?** (Google, then Microsoft Graph, then
  generic CalDAV, then ICS; see
  [implementation order](#recommended-implementation-order).)
- **Read-only vs two-way default?** (Ship M1 read-only. Make two-way opt-in
  per calendar via `sync_direction`; two-way is where the conflict cost is.)
- **Per-instance BYO OAuth apps vs one shared Kalendee app?** (Per-instance
  BYO credentials; avoids verification, quota, and liability. Revisit only if
  a managed shared app becomes a product goal.)
- **Ship ICS subscriptions early?** (Yes — S effort, and it is the only
  read-only path for providers with no public API.)
- **Where does the CalDAV library live?** (Its own pure-KMP module per
  [AGENTS.md](../AGENTS.md) and [GOALS.md](../GOALS.md); the sync adapter
  should be its first consumer.)

Ways to contribute:

- Review the provider matrix and drop or raise providers.
- Provide test accounts and OAuth client credentials for Google and Microsoft
  verification testing.
- Implement from the repo starting points below; the settings/admin patterns
  are already established.

| Area | Start here |
| --- | --- |
| Settings and admin toggle pattern | [AuthService.kt](../server/src/main/kotlin/dev/kolektiv/kalendee/auth/AuthService.kt), [Pages.kt](../server/src/main/kotlin/dev/kolektiv/kalendee/web/Pages.kt), [settings page](../server/pack/src/pages/kalendee/settings/+page.svelte) |
| Calendar and event models | [Models.kt](../core/src/commonMain/kotlin/dev/kolektiv/kalendee/calendar/Models.kt) |
| Recurrence limits | [Recurrence.kt](../core/src/commonMain/kotlin/dev/kolektiv/kalendee/calendar/Recurrence.kt) |
| RSS and public sharing | [RssRoutes.kt](../server/src/main/kotlin/dev/kolektiv/kalendee/api/RssRoutes.kt) |
| Reminders | [ReminderService.kt](../server/src/main/kotlin/dev/kolektiv/kalendee/reminders/ReminderService.kt) |
| Server config | [AppSettings.kt](../server/src/main/kotlin/dev/kolektiv/kalendee/config/AppSettings.kt), [application.conf.example](../application.conf.example) |
| Latest schema change | [V16__organizations.sql](../server/src/main/resources/db/migration/V16__organizations.sql) |
