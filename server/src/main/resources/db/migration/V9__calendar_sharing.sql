CREATE TABLE calendar_shares (
  calendar_id UUID NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  permission TEXT NOT NULL DEFAULT 'read',
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (calendar_id, user_id)
);
CREATE INDEX calendar_shares_user_idx ON calendar_shares (user_id);

CREATE TABLE calendar_followers (
  calendar_id UUID NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (calendar_id, user_id)
);
CREATE INDEX calendar_followers_user_idx ON calendar_followers (user_id);

ALTER TABLE calendars ADD COLUMN public_link_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE calendars ADD COLUMN public_link_token TEXT;
CREATE UNIQUE INDEX calendars_public_link_token_key ON calendars (public_link_token);
