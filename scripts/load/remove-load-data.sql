BEGIN;

CREATE TEMP TABLE load_teams AS SELECT id FROM teams WHERE name LIKE 'LOAD-%';
CREATE TEMP TABLE load_profiles AS SELECT id FROM crm_user_profiles WHERE display_name LIKE 'LOAD-%';
CREATE TEMP TABLE load_organizations AS SELECT id FROM organizations WHERE name LIKE 'LOAD-%';
CREATE TEMP TABLE load_interactions AS SELECT id FROM interactions WHERE organization_id IN (SELECT id FROM load_organizations);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM organizations o
        WHERE (o.id IN (SELECT id FROM load_organizations)) <> (o.team_id IN (SELECT id FROM load_teams))
    ) THEN
        RAISE EXCEPTION 'load organizations and teams are mixed with demo data';
    END IF;
    IF EXISTS (
        SELECT 1 FROM crm_user_profiles p
        WHERE p.id IN (SELECT id FROM load_profiles) AND p.team_id NOT IN (SELECT id FROM load_teams)
    ) THEN
        RAISE EXCEPTION 'a load profile belongs to a demo team';
    END IF;
    IF EXISTS (
        SELECT 1 FROM interaction_events e
        WHERE e.interaction_id IN (SELECT id FROM load_interactions) AND e.actor_profile_id NOT IN (SELECT id FROM load_profiles)
    ) THEN
        RAISE EXCEPTION 'a demo profile acted on load interactions';
    END IF;
END $$;

SELECT 'report-file ' || result_storage_key
FROM report_jobs
WHERE owner_profile_id IN (SELECT id FROM load_profiles) AND result_storage_key IS NOT NULL;

SELECT 'attachment-file ' || storage_key
FROM attachments
WHERE interaction_id IN (SELECT id FROM load_interactions);

DELETE FROM report_jobs WHERE owner_profile_id IN (SELECT id FROM load_profiles);
DELETE FROM source_records WHERE organization_id IN (SELECT id FROM load_organizations);
DELETE FROM attachments WHERE interaction_id IN (SELECT id FROM load_interactions);
DELETE FROM interaction_events WHERE interaction_id IN (SELECT id FROM load_interactions);
DELETE FROM interactions WHERE id IN (SELECT id FROM load_interactions);
DELETE FROM contacts WHERE organization_id IN (SELECT id FROM load_organizations);
DELETE FROM organization_assignment_events WHERE organization_id IN (SELECT id FROM load_organizations);
DELETE FROM organization_team_events WHERE organization_id IN (SELECT id FROM load_organizations);
DELETE FROM organizations WHERE id IN (SELECT id FROM load_organizations);
DELETE FROM crm_profile_events WHERE profile_id IN (SELECT id FROM load_profiles);
DELETE FROM command_idempotency_records WHERE actor_profile_id IN (SELECT id FROM load_profiles);
DELETE FROM workflow_templates WHERE team_id IN (SELECT id FROM load_teams);
DELETE FROM crm_user_profiles WHERE id IN (SELECT id FROM load_profiles);
DELETE FROM teams WHERE id IN (SELECT id FROM load_teams);
DELETE FROM products WHERE name LIKE 'LOAD-%';
DELETE FROM vendors WHERE name LIKE 'LOAD-%';
DELETE FROM programs WHERE name LIKE 'LOAD-%';
DELETE FROM directions WHERE name LIKE 'LOAD-%';
DELETE FROM spring_session WHERE principal_name LIKE 'load-%';

COMMIT;
