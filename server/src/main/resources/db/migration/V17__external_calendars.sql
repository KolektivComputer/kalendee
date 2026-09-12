CREATE TABLE calendar_connections (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider TEXT NOT NULL,
  external_account_id TEXT NOT NULL,
  account_email TEXT,
  display_name TEXT,
  access_token_ciphertext TEXT NOT NULL,
  access_token_nonce TEXT NOT NULL,
  refresh_token_ciphertext TEXT,
  refresh_token_nonce TEXT,
  token_key_version INTEGER NOT NULL DEFAULT 1,
  token_expires_at TIMESTAMPTZ,
  scopes TEXT,
  status TEXT NOT NULL DEFAULT 'active',
  last_sync_at TIMESTAMPTZ,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX calendar_connections_user_provider_account_key
  ON calendar_connections (user_id, provider, external_account_id);
CREATE INDEX calendar_connections_user_idx ON calendar_connections (user_id);

CREATE TABLE external_calendars (
  id UUID PRIMARY KEY,
  connection_id UUID NOT NULL REFERENCES calendar_connections(id) ON DELETE CASCADE,
  external_id TEXT NOT NULL,
  calendar_id UUID NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
  external_name TEXT,
  sync_direction TEXT NOT NULL DEFAULT 'pull',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  sync_token TEXT,
  last_sync_at TIMESTAMPTZ,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX external_calendars_connection_external_key
  ON external_calendars (connection_id, external_id);
CREATE UNIQUE INDEX external_calendars_calendar_key ON external_calendars (calendar_id);
CREATE INDEX external_calendars_connection_idx ON external_calendars (connection_id);

CREATE TABLE oauth_states (
  state TEXT PRIMARY KEY,
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  provider TEXT NOT NULL,
  code_verifier_ciphertext TEXT NOT NULL,
  code_verifier_nonce TEXT NOT NULL,
  redirect_uri TEXT NOT NULL,
  return_to TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  used_at TIMESTAMPTZ
);
CREATE INDEX oauth_states_expires_idx ON oauth_states (expires_at);

CREATE TABLE external_event_tombstones (
  id UUID PRIMARY KEY,
  external_calendar_id UUID NOT NULL REFERENCES external_calendars(id) ON DELETE CASCADE,
  external_uid TEXT NOT NULL,
  deleted_at TIMESTAMPTZ NOT NULL,
  uploaded_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX external_event_tombstones_calendar_uid_key
  ON external_event_tombstones (external_calendar_id, external_uid);
CREATE INDEX external_event_tombstones_upload_idx
  ON external_event_tombstones (external_calendar_id, uploaded_at);

ALTER TABLE events ADD COLUMN external_calendar_id UUID REFERENCES external_calendars(id) ON DELETE SET NULL;
ALTER TABLE events ADD COLUMN external_uid TEXT;
ALTER TABLE events ADD COLUMN external_etag TEXT;
ALTER TABLE events ADD COLUMN external_updated_at TIMESTAMPTZ;
CREATE UNIQUE INDEX events_external_calendar_uid_key ON events (external_calendar_id, external_uid);

ALTER TABLE users ADD COLUMN email_normalized TEXT;
CREATE UNIQUE INDEX users_email_normalized_key ON users (email_normalized);
