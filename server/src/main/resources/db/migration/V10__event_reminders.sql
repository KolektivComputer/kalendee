ALTER TABLE users ADD COLUMN notify_at_start BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE user_reminder_defaults (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  offset_seconds INTEGER NOT NULL,
  PRIMARY KEY (user_id, offset_seconds)
);

CREATE TABLE event_reminder_settings (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  use_defaults BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, event_id)
);

CREATE TABLE event_reminders (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  offset_seconds INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, event_id, offset_seconds)
);
