CREATE INDEX organizations_owner_manager_name_idx ON organizations (owner_manager_id, name, id);
CREATE INDEX organizations_team_name_idx ON organizations (team_id, name, id);
CREATE INDEX organizations_owner_manager_updated_at_idx ON organizations (owner_manager_id, updated_at DESC, id);
CREATE INDEX organizations_team_updated_at_idx ON organizations (team_id, updated_at DESC, id);
