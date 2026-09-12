ALTER TABLE events ADD COLUMN open_rsvp BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE event_attendees (
  id UUID PRIMARY KEY,
  event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  email TEXT,
  name TEXT,
  status TEXT NOT NULL DEFAULT 'invited',
  invited_by UUID REFERENCES users(id) ON DELETE SET NULL,
  token_hash TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  responded_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX event_attendees_event_user_key ON event_attendees (event_id, user_id);
CREATE UNIQUE INDEX event_attendees_token_key ON event_attendees (token_hash);
CREATE INDEX event_attendees_user_status_idx ON event_attendees (user_id, status);
