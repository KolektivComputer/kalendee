CREATE TABLE organization_teams (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  slug TEXT NOT NULL,
  name TEXT NOT NULL,
  description TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX organization_teams_org_slug_key ON organization_teams (organization_id, slug);
CREATE INDEX organization_teams_org_idx ON organization_teams (organization_id);
CREATE TABLE organization_team_members (
  team_id UUID NOT NULL REFERENCES organization_teams(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL DEFAULT 'member',
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (team_id, user_id)
);
CREATE INDEX organization_team_members_user_idx ON organization_team_members (user_id);
CREATE TABLE calendar_team_grants (
  calendar_id UUID NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
  team_id UUID NOT NULL REFERENCES organization_teams(id) ON DELETE CASCADE,
  permission TEXT NOT NULL DEFAULT 'read',
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (calendar_id, team_id)
);
CREATE INDEX calendar_team_grants_team_idx ON calendar_team_grants (team_id);
