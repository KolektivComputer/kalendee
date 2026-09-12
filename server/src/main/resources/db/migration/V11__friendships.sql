CREATE TABLE friendships (
  id UUID PRIMARY KEY,
  requester_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  addressee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  responded_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX friendships_pair_key ON friendships (requester_id, addressee_id);
CREATE INDEX friendships_addressee_status_idx ON friendships (addressee_id, status);
CREATE INDEX friendships_requester_status_idx ON friendships (requester_id, status);
