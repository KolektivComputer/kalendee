---
title: Discord OAuth
description: >-
  Set up the Discord integration: application credentials, the redirect URI
  derived from app.baseUrl, token-vault key rotation, OAuth sign-in, and
  server-event import.
---

Kalendee uses Discord for two related features:

- **Account linking / login** via Discord OAuth (scopes `identify` + `guilds`).
- **Server event import**: a read-only mirror of a Discord guild's scheduled
  events, read with a bot token. Discord has no public calendar API, so the
  import is one-way (Discord → Kalendee).

The implementation lives in
[`server/src/main/kotlin/dev/kolektiv/kalendee/oauth/`](https://github.com/KolektivComputer/kalendee/tree/main/server/src/main/kotlin/dev/kolektiv/kalendee/oauth).
For the product-level description see
[External calendars](/docs/product/external-calendars).

## Create the Discord application

1. Open the [Discord Developer Portal](https://discord.com/developers/applications)
   and create a new application.
2. On the **OAuth2** page, note the **Client ID** and **Client Secret**.
3. Add a redirect URI exactly matching
   `<app.baseUrl>/api/v1/oauth/discord/callback`, for example
   `https://calendar.example.com/api/v1/oauth/discord/callback`. The server
   derives this from `app.baseUrl` and never accepts a `redirect_uri` from the
   request.
4. (For event import) create a **Bot** and copy its token, then invite it to the
   guilds you want to import.

## Server keys

| Key | Env fallback | Required for | Meaning |
| --- | --- | --- | --- |
| `oauth.discord.clientId` | `KALENDEE_DISCORD_CLIENT_ID` | Linking and login | Discord OAuth client id. Blank disables the provider entirely. |
| `oauth.discord.clientSecret` | `KALENDEE_DISCORD_CLIENT_SECRET` | Linking and login | Discord OAuth client secret. |
| `oauth.discord.botToken` | `KALENDEE_DISCORD_BOT_TOKEN` | Event import | Bot token used to read guild scheduled events. Optional: linking works without it. |
| `oauth.secretKey` | `KALENDEE_SECRET_KEY` | Any connection | Base64 AES key (16/24/32 bytes) stored as vault version `1`. |
| `oauth.secretKeys` | `KALENDEE_SECRET_KEYS` | Key rotation | JSON `{"<version>":"<base64>",...}` map; the highest version seals new tokens. |

The redirect URI is computed as:

```text
{app.baseUrl trimmed of trailing slash}/api/v1/oauth/discord/callback
```

So a misconfigured `app.baseUrl` produces a redirect URI mismatch at Discord. The
same value is registered for the local dev stack as
`http://localhost:8080/api/v1/oauth/discord/callback`. If `app.baseUrl` is blank,
the server uses the relative path `/api/v1/oauth/discord/callback`, which Discord
will still require to match a registered URI exactly.

### Enable the provider

```hocon
oauth {
    discord {
        clientId = "123456789012345678"
        clientId = ${?KALENDEE_DISCORD_CLIENT_ID}
        clientSecret = "discord-client-secret"
        clientSecret = ${?KALENDEE_DISCORD_CLIENT_SECRET}
        botToken = "discord-bot-token"
        botToken = ${?KALENDEE_DISCORD_BOT_TOKEN}
    }
    secretKey = "base64-32-byte-key"
    secretKey = ${?KALENDEE_SECRET_KEY}
}
```

Keep the client secret, bot token, and vault key in `.env`, not in a committed
config file. A blank `clientId` disables the provider and hides its buttons.

## The bot

The bot is used **only** to read guild scheduled events; the user token from
OAuth never reads them. Generate an invite URL with the `bot` scope and the
minimal permissions needed to view scheduled events, then invite it to each
guild. The settings UI shows a "bot missing" state with a per-guild invite link
until the bot is present. If you never import events, you can omit
`oauth.discord.botToken`.

## Token vault and key rotation

Connection tokens are encrypted with AES-256-GCM, a unique 12-byte nonce per
sealed value, and an integer key version. The vault fails closed: with no key
configured, connecting a provider throws instead of storing plaintext.

| Configuration | Key versions | Sealing key |
| --- | --- | --- |
| `oauth.secretKey = "<base64>"` | `{1}` | version `1` |
| `oauth.secretKeys = '{"1":"...","2":"..."}'` | `{1,2}` | highest version (`2`) |
| Both set | `{1}` from `secretKey`, merged with `secretKeys`; `secretKeys` wins on conflict | highest |

Generate a key with:

```bash
openssl rand -base64 32
```

Rotation procedure:

1. Generate a new key and add it under a new version in
   `oauth.secretKeys`, keeping all older versions:

   ```hocon
   oauth {
       secretKeys = ${?KALENDEE_SECRET_KEYS}   # {"1":"<old>","2":"<new>"}
   }
   ```

2. Restart. New and refreshed tokens are sealed with version `2`; tokens sealed
   with version `1` remain readable.
3. Keep version `1` until every connection has refreshed or been re-linked, then
   remove it. Losing a version means any token still sealed with it becomes
   undecryptable and the affected connection must be re-authorized.

Never commit keys. `secretKey` and `secretKeys` are trimmed, and blank values
are treated as unset.

## OAuth sign-in and registration

Discord buttons appear when `oauth.discord.clientId` is non-blank. Two gates
control whether an OAuth sign-in may **create** an account:

1. `auth.oauthRegistration` (initial value; the admin page can override it).
2. `auth.registration` must also allow sign-ups (`first-user` only while the
   users table is empty, `open`, or the runtime override).

Existing signed-in users can always link a Discord account regardless of the
registration gates. On the OAuth callback the server:

- consumes the single-use `state` (10-minute TTL, bound to the signed-in user);
- exchanges the code using PKCE `S256`;
- resolves the user: an explicit linked user wins, otherwise a verified email
  match, otherwise — if registration is allowed — a new account;
- rejects with `registration_closed` or `email_taken` when those apply.

The exact scopes requested are `identify` and `guilds`; no email scope is
requested, so Discord accounts have no email and are matched by provider
identity rather than email.

## Event import

Event import creates a read-only `Discord · <guild>` calendar and mirrors the
guild's scheduled events, using the bot to read them. Requirements:

- `oauth.discord.botToken` set and the bot invited to the guild.
- The signed-in user is a member of the guild (listed via the `guilds` scope).

Recurring events are materialized per occurrence inside a rolling window;
unsupported recurrence rules fall back to the master event with an explanatory
note. Removing an import keeps the local calendar and detaches its events. The
UI lives under **Settings → Connected Accounts** in the Keel web UI.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Discord buttons missing | `oauth.discord.clientId` blank. | Set the client id and restart. |
| `invalid_redirect_uri` from Discord | The registered URI differs from `app.baseUrl` + `/api/v1/oauth/discord/callback`. | Fix `app.baseUrl` and re-register the exact URI. |
| Linking fails with a token-vault error | No `oauth.secretKey`/`secretKeys` configured. | Set a base64 key and restart. |
| Sign-in returns `registration_closed` | `oauthRegistration` or `registration` is closed. | Enable the admin toggle or set `auth.oauthRegistration = true`. |
| Event import unavailable | Bot token missing or bot not in the guild. | Set `oauth.discord.botToken` and invite the bot. |
| Connection flips to `needs_reauth` | Discord rejected the refresh token, or a vault key version was removed. | Reconnect the account; restore removed key versions. |

## Related pages

- [Configuration](/docs/self-hosting/configuration) — `oauth.*` key reference.
- [Environment variables](/docs/self-hosting/environment) — `KALENDEE_DISCORD_*`
  and `KALENDEE_SECRET_*`.
- [External calendars](/docs/product/external-calendars) — provider matrix and
  data model.
- [Security](/docs/self-hosting/security) — secret handling and rotation.
