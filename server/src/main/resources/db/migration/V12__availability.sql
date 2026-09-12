ALTER TABLE calendars ADD COLUMN requests_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE calendars ADD COLUMN slot_minutes INTEGER NOT NULL DEFAULT 60;
ALTER TABLE calendars ADD COLUMN access_mode TEXT NOT NULL DEFAULT 'inherit';
ALTER TABLE users ADD COLUMN public_access TEXT NOT NULL DEFAULT 'inherit';

CREATE TABLE calendar_availability (
  id UUID PRIMARY KEY,
  calendar_id UUID NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
  weekday INTEGER NOT NULL,
  start_minute INTEGER NOT NULL,
  end_minute INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX calendar_availability_calendar_idx ON calendar_availability (calendar_id, weekday);

CREATE TABLE time_slot_requests (
  id UUID PRIMARY KEY,
  calendar_id UUID NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
  requester_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  start_at TIMESTAMPTZ NOT NULL,
  end_at TIMESTAMPTZ NOT NULL,
  message TEXT,
  status TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  responded_at TIMESTAMPTZ,
  responded_by UUID REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX time_slot_requests_calendar_idx ON time_slot_requests (calendar_id, created_at);
CREATE INDEX time_slot_requests_requester_idx ON time_slot_requests (requester_id, created_at);
