DO $$
BEGIN
    IF (SELECT count(*) FROM load_users) <> 50 THEN
        RAISE EXCEPTION 'load users are incomplete';
    END IF;
    IF (SELECT count(*) FROM workflow_template_stages s JOIN workflow_templates t ON t.id = s.template_id WHERE t.default_template) < 10 THEN
        RAISE EXCEPTION 'default workflow template has fewer than 10 stages';
    END IF;
END $$;

CREATE TEMP TABLE load_teams AS
SELECT n, gen_random_uuid() AS id FROM generate_series(1, 5) n;

INSERT INTO teams (id, name) SELECT id, 'LOAD-команда ' || n FROM load_teams;

CREATE TEMP TABLE load_profiles AS
SELECT u.n,
       gen_random_uuid() AS id,
       u.subject,
       ((u.n - 1) % 5) + 1 AS team_n,
       CASE WHEN u.n <= 5 THEN 'LEADER' ELSE 'USER' END AS role,
       CASE WHEN u.n <= 5 THEN 'LOAD-руководитель ' ELSE 'LOAD-КАМ ' END || lpad(u.n::text, 2, '0') AS display_name
FROM load_users u;

INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, active)
SELECT p.id, :'issuer', p.subject, p.display_name, p.role, t.id, TRUE
FROM load_profiles p
JOIN load_teams t ON t.n = p.team_n;

CREATE TEMP TABLE load_directions AS
SELECT n, gen_random_uuid() AS id FROM generate_series(1, 3) n;

INSERT INTO directions (id, name) SELECT id, 'LOAD-направление ' || n FROM load_directions;

CREATE TEMP TABLE load_programs AS
SELECT n, gen_random_uuid() AS id, ((n - 1) % 3) + 1 AS direction_n FROM generate_series(1, 6) n;

INSERT INTO programs (id, direction_id, name)
SELECT p.id, d.id, 'LOAD-программа ' || p.n
FROM load_programs p
JOIN load_directions d ON d.n = p.direction_n;

CREATE TEMP TABLE load_vendor AS SELECT gen_random_uuid() AS id;

INSERT INTO vendors (id, name) SELECT id, 'LOAD-вендор' FROM load_vendor;

CREATE TEMP TABLE load_products AS
SELECT n, gen_random_uuid() AS id FROM generate_series(1, 5) n;

INSERT INTO products (id, vendor_id, name)
SELECT p.id, v.id, 'LOAD-продукт ' || p.n FROM load_products p CROSS JOIN load_vendor v;

CREATE TEMP TABLE load_organizations AS
SELECT o.n,
       gen_random_uuid() AS id,
       gen_random_uuid() AS contact_id,
       ((o.n - 1) % 5) + 1 AS team_n,
       CASE WHEN (o.n - 1) / 5 = 19 THEN NULL ELSE 5 * ((((o.n - 1) / 5) % 9) + 1) + ((o.n - 1) % 5) + 1 END AS owner_n
FROM generate_series(1, 100) AS o(n);

INSERT INTO organizations (id, name, type, team_id, owner_manager_id)
SELECT o.id, 'LOAD-вуз ' || lpad(o.n::text, 3, '0'), 'UNIVERSITY', t.id, p.id
FROM load_organizations o
JOIN load_teams t ON t.n = o.team_n
LEFT JOIN load_profiles p ON p.n = o.owner_n;

INSERT INTO contacts (id, organization_id, name, position, email, created_by)
SELECT o.contact_id, o.id, 'LOAD-контакт ' || lpad(o.n::text, 3, '0'), 'Проректор', 'contact-' || o.n || '@load.rtk.local', actor.id
FROM load_organizations o
JOIN load_profiles actor ON actor.n = coalesce(o.owner_n, o.team_n);

CREATE TEMP TABLE load_interactions AS
SELECT x.n,
       gen_random_uuid() AS id,
       o.id AS organization_id,
       o.contact_id,
       owner.id AS owner_id,
       actor.id AS actor_id,
       (x.n - 1) % 9 AS transitions,
       date_trunc('minute', now()) - make_interval(days => 20 + (x.n * 37) % 345, hours => (x.n * 7) % 24) AS created_at
FROM generate_series(1, 1000) AS x(n)
JOIN load_organizations o ON o.n = ((x.n - 1) % 100) + 1
LEFT JOIN load_profiles owner ON owner.n = o.owner_n
JOIN load_profiles actor ON actor.n = coalesce(o.owner_n, o.team_n);

CREATE TEMP TABLE load_stages AS
SELECT i.id AS interaction_id,
       s.id AS template_stage_id,
       gen_random_uuid() AS id,
       s.stage_order,
       s.name,
       s.optional,
       row_number() OVER (PARTITION BY i.id ORDER BY s.stage_order) - 1 AS position
FROM load_interactions i
CROSS JOIN workflow_template_stages s
JOIN workflow_templates t ON t.id = s.template_id AND t.default_template;

CREATE TEMP TABLE load_events AS
SELECT i.id AS interaction_id,
       e AS version,
       gen_random_uuid() AS id,
       gen_random_uuid() AS command_id,
       CASE WHEN e = 0 THEN 'CREATED' WHEN e <= i.transitions THEN 'TRANSITIONED' ELSE 'COMMENTED' END AS type,
       i.created_at + (now() - interval '1 hour' - i.created_at) * e / 10 AS occurred_at,
       i.actor_id,
       i.owner_id,
       i.transitions
FROM load_interactions i
CROSS JOIN generate_series(0, 9) e;

INSERT INTO interactions (
    id, organization_id, title, current_stage_id, next_action, next_action_at, program_id, last_contact_at,
    version, created_by, created_at, updated_at
)
SELECT i.id,
       i.organization_id,
       'LOAD-взаимодействие ' || lpad(i.n::text, 4, '0'),
       current_stage.id,
       CASE WHEN i.n % 4 = 3 THEN NULL ELSE 'LOAD-следующий шаг ' || i.n END,
       CASE i.n % 4
           WHEN 0 THEN now() - make_interval(days => (i.n % 10) + 1)
           WHEN 1 THEN now() + make_interval(hours => (i.n % 3) * 8 + 1)
           WHEN 2 THEN now() + make_interval(days => 10 + i.n % 30)
       END,
       CASE WHEN i.n % 5 = 0 THEN NULL ELSE program.id END,
       last_event.occurred_at,
       9,
       i.actor_id,
       i.created_at,
       last_event.occurred_at
FROM load_interactions i
JOIN load_stages current_stage ON current_stage.interaction_id = i.id AND current_stage.position = i.transitions
JOIN load_events last_event ON last_event.interaction_id = i.id AND last_event.version = 9
LEFT JOIN load_programs program ON program.n = (i.n % 6) + 1;

INSERT INTO interaction_stages (id, interaction_id, stage_order, name, optional)
SELECT id, interaction_id, stage_order, name, optional FROM load_stages;

INSERT INTO interaction_stage_transitions (interaction_id, from_stage_id, to_stage_id, comment_required)
SELECT from_stage.interaction_id, from_stage.id, to_stage.id, transition.comment_required
FROM workflow_template_transitions transition
JOIN workflow_templates t ON t.id = transition.template_id AND t.default_template
JOIN load_stages from_stage ON from_stage.template_stage_id = transition.from_stage_id
JOIN load_stages to_stage ON to_stage.template_stage_id = transition.to_stage_id
    AND to_stage.interaction_id = from_stage.interaction_id;

INSERT INTO interaction_contacts (interaction_id, organization_id, contact_id)
SELECT id, organization_id, contact_id FROM load_interactions;

INSERT INTO product_agreements (id, interaction_id, product_id)
SELECT gen_random_uuid(), i.id, p.id
FROM load_interactions i
JOIN load_products p ON (i.n % 3 >= 1 AND p.n = (i.n % 5) + 1) OR (i.n % 3 = 2 AND p.n = ((i.n + 1) % 5) + 1);

INSERT INTO command_idempotency_records (id, actor_profile_id, operation, idempotency_key, request_fingerprint, created_at)
SELECT command_id,
       actor_id,
       CASE type WHEN 'CREATED' THEN 'CREATE_INTERACTION' WHEN 'TRANSITIONED' THEN 'TRANSITION_INTERACTION' ELSE 'COMMENT_INTERACTION' END,
       'LOAD-seed-' || id,
       encode(sha256(convert_to(id::text, 'UTF8')), 'hex'),
       occurred_at
FROM load_events;

INSERT INTO interaction_events (
    id, interaction_id, command_id, type, stage_id, stage_name_snapshot,
    from_stage_id, from_stage_name_snapshot, to_stage_id, to_stage_name_snapshot,
    comment, actor_profile_id, owner_manager_id_snapshot, version, occurred_at
)
SELECT e.id,
       e.interaction_id,
       e.command_id,
       e.type,
       stage.id,
       stage.name,
       from_stage.id,
       from_stage.name,
       CASE WHEN e.type = 'COMMENTED' THEN NULL ELSE stage.id END,
       CASE WHEN e.type = 'COMMENTED' THEN NULL ELSE stage.name END,
       CASE WHEN e.type = 'COMMENTED' THEN 'LOAD-комментарий ' || e.version END,
       e.actor_id,
       e.owner_id,
       e.version,
       e.occurred_at
FROM load_events e
JOIN load_stages stage ON stage.interaction_id = e.interaction_id
    AND stage.position = CASE WHEN e.type = 'CREATED' THEN 0 WHEN e.type = 'TRANSITIONED' THEN e.version ELSE e.transitions END
LEFT JOIN load_stages from_stage ON e.type = 'TRANSITIONED' AND from_stage.interaction_id = e.interaction_id
    AND from_stage.position = e.version - 1;

INSERT INTO source_records (
    id, source, record_type, external_id, external_updated_at, submitted_at, external_status, payload, status,
    organization_id, program_id, applications_count, created_at, updated_at
)
SELECT gen_random_uuid(),
       'WEBSITE',
       'learning_application',
       'LOAD-la-' || lpad(a.n::text, 4, '0'),
       a.submitted_at,
       a.submitted_at,
       'new',
       jsonb_build_object(
           'externalId', 'LOAD-la-' || lpad(a.n::text, 4, '0'),
           'type', 'learning_application',
           'createdAt', a.submitted_at,
           'updatedAt', a.submitted_at,
           'status', 'new',
           'organization', jsonb_build_object('name', 'LOAD-вуз ' || lpad(o.n::text, 3, '0')),
           'program', CASE WHEN program.id IS NULL THEN NULL ELSE jsonb_build_object('name', 'LOAD-программа ' || program.n) END,
           'applicationsCount', a.applications
       )::text,
       'APPLIED',
       o.id,
       program.id,
       a.applications,
       a.submitted_at,
       a.submitted_at
FROM (
    SELECT n,
           date_trunc('minute', now()) - make_interval(days => (n * 13) % 360, hours => (n * 5) % 24) AS submitted_at,
           1 + n % 5 AS applications
    FROM generate_series(1, 2000) n
) a
JOIN load_organizations o ON o.n = ((a.n - 1) % 100) + 1
LEFT JOIN load_programs program ON a.n % 10 <> 0 AND program.n = ((a.n * 7) % 6) + 1;

SELECT format('teams=%s profiles=%s organizations=%s interactions=%s events=%s applications=%s',
    (SELECT count(*) FROM teams WHERE name LIKE 'LOAD-%'),
    (SELECT count(*) FROM crm_user_profiles WHERE display_name LIKE 'LOAD-%'),
    (SELECT count(*) FROM organizations WHERE name LIKE 'LOAD-%'),
    (SELECT count(*) FROM interactions WHERE title LIKE 'LOAD-%'),
    (SELECT count(*) FROM interaction_events e JOIN interactions i ON i.id = e.interaction_id WHERE i.title LIKE 'LOAD-%'),
    (SELECT count(*) FROM source_records WHERE external_id LIKE 'LOAD-%'));
