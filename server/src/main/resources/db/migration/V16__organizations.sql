CREATE TABLE organizations (
  id UUID PRIMARY KEY,
  slug TEXT NOT NULL,
  display_name TEXT NOT NULL,
  description TEXT,
  avatar_key TEXT,
  avatar_updated_at TIMESTAMPTZ,
  visibility TEXT NOT NULL DEFAULT 'private',
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX organizations_slug_key ON organizations (slug);
CREATE TABLE organization_members (
  organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (organization_id, user_id)
);
CREATE INDEX organization_members_user_idx ON organization_members (user_id);
CREATE TABLE organization_invitations (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  inviter_id UUID REFERENCES users(id) ON DELETE SET NULL,
  invitee_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  email TEXT,
  role TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  token_hash TEXT,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  responded_at TIMESTAMPTZ
);
CREATE INDEX organization_invitations_org_status_idx ON organization_invitations (organization_id, status);
CREATE INDEX organization_invitations_invitee_idx ON organization_invitations (invitee_user_id, status);
CREATE INDEX organization_invitations_email_idx ON organization_invitations (email, status);
CREATE UNIQUE INDEX organization_invitations_token_key ON organization_invitations (token_hash);
ALTER TABLE calendars ADD COLUMN organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE;
CREATE INDEX calendars_organization_id_idx ON calendars (organization_id);
