ALTER TABLE time_slot_requests ALTER COLUMN requester_id DROP NOT NULL;
ALTER TABLE time_slot_requests ADD COLUMN requester_name TEXT;
ALTER TABLE time_slot_requests ADD COLUMN requester_email TEXT;
