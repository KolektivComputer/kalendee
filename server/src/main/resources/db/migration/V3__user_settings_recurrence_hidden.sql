ALTER TABLE users
    ADD COLUMN display_name TEXT,
    ADD COLUMN time_zone TEXT NOT NULL DEFAULT 'UTC';

UPDATE users SET display_name = username WHERE display_name IS NULL;

ALTER TABLE users
    ALTER COLUMN display_name SET NOT NULL;

ALTER TABLE events
    ADD COLUMN url TEXT,
    ADD COLUMN recurrence_frequency TEXT,
    ADD COLUMN recurrence_interval INTEGER,
    ADD COLUMN recurrence_until TIMESTAMPTZ,
    ADD COLUMN recurrence_count INTEGER;

CREATE TABLE calendar_hidden (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    calendar_id UUID NOT NULL REFERENCES calendars (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, calendar_id)
);
