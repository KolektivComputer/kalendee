ALTER TABLE calendars
    ADD COLUMN color TEXT NOT NULL DEFAULT '#4c6a7a';

ALTER TABLE users
    ADD COLUMN show_holidays BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE holiday_subscriptions (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    holiday_id TEXT NOT NULL,
    PRIMARY KEY (user_id, holiday_id)
);

CREATE TABLE custom_holidays (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    month INTEGER NOT NULL,
    day INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX custom_holidays_user_id_idx ON custom_holidays (user_id);
