CREATE TABLE discord_event_routes (
    id UUID PRIMARY KEY,
    external_calendar_id UUID NOT NULL REFERENCES external_calendars(id) ON DELETE CASCADE,
    event_id TEXT NOT NULL,
    calendar_id UUID REFERENCES calendars(id) ON DELETE CASCADE,
    skipped BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT discord_event_routes_target_check
        CHECK ((skipped AND calendar_id IS NULL) OR (NOT skipped AND calendar_id IS NOT NULL)),
    UNIQUE (external_calendar_id, event_id)
);
CREATE INDEX discord_event_routes_calendar_idx ON discord_event_routes (calendar_id);
