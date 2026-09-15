ALTER TABLE events ADD COLUMN external_exception_id TEXT;
CREATE INDEX events_external_exception_idx ON events (external_calendar_id, external_exception_id);
