ALTER TABLE calendars ADD COLUMN rsvp_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE calendars ADD COLUMN anonymous_rsvp_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE events ADD COLUMN rsvp_override BOOLEAN;
ALTER TABLE events ADD COLUMN anonymous_rsvp_override BOOLEAN;
UPDATE events SET anonymous_rsvp_override = TRUE WHERE open_rsvp = TRUE;
ALTER TABLE events DROP COLUMN open_rsvp;
