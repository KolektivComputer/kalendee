CREATE TABLE calendars (
    id UUID PRIMARY KEY,
    display_name TEXT NOT NULL,
    description TEXT,
    time_zone TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE events (
    id UUID PRIMARY KEY,
    calendar_id UUID NOT NULL REFERENCES calendars (id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    description TEXT,
    location TEXT,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    all_day BOOLEAN NOT NULL,
    time_zone TEXT,
    status TEXT NOT NULL,
    etag TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX events_calendar_id_start_at_end_at_idx
    ON events (calendar_id, start_at, end_at);
