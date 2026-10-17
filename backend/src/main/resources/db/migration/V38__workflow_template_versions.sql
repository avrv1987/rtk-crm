CREATE TABLE workflow_template_versions (
    template_id UUID NOT NULL REFERENCES workflow_templates(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL CHECK (version_number >= 1),
    name VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    change_comment VARCHAR(1000),
    created_by UUID REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (template_id, version_number)
);

ALTER TABLE interactions ADD COLUMN workflow_template_id UUID REFERENCES workflow_templates(id) ON DELETE SET NULL;
ALTER TABLE interactions ADD COLUMN workflow_version INTEGER CHECK (workflow_version >= 1);
ALTER TABLE interaction_stages ADD COLUMN template_stage_id UUID;

CREATE INDEX interactions_workflow_idx ON interactions (workflow_template_id, workflow_version);

INSERT INTO workflow_template_versions (template_id, version_number, name, content, change_comment, created_by, created_at)
SELECT t.id, 1, t.name,
       json_build_object(
           'stages', COALESCE((
               SELECT json_agg(json_build_object(
                          'id', s.id, 'name', s.name, 'order', s.stage_order, 'optional', s.optional
                      ) ORDER BY s.stage_order)
               FROM workflow_template_stages s
               WHERE s.template_id = t.id
           ), '[]'::json),
           'transitions', COALESCE((
               SELECT json_agg(json_build_object(
                          'fromStageId', r.from_stage_id, 'toStageId', r.to_stage_id,
                          'commentRequired', r.comment_required
                      ) ORDER BY r.from_stage_id, r.to_stage_id)
               FROM workflow_template_transitions r
               WHERE r.template_id = t.id
           ), '[]'::json)
       )::text,
       'Исходная версия',
       NULL,
       t.updated_at
FROM workflow_templates t;

WITH template_signatures AS (
    SELECT s.template_id,
           string_agg(s.name || CASE WHEN s.optional THEN '?' ELSE '!' END, chr(31) ORDER BY s.stage_order) AS signature
    FROM workflow_template_stages s
    GROUP BY s.template_id
), interaction_signatures AS (
    SELECT s.interaction_id,
           string_agg(s.name || CASE WHEN s.optional THEN '?' ELSE '!' END, chr(31) ORDER BY s.stage_order) AS signature
    FROM interaction_stages s
    GROUP BY s.interaction_id
), candidates AS (
    SELECT i.id AS interaction_id,
           t.id AS template_id,
           ROW_NUMBER() OVER (
               PARTITION BY i.id
               ORDER BY CASE WHEN tsig.signature = isig.signature THEN 0 ELSE 1 END,
                        CASE WHEN t.id = team.default_workflow_template_id THEN 0
                             WHEN t.default_template THEN 1
                             WHEN t.team_id IS NOT NULL THEN 2
                             ELSE 3 END,
                        t.id
           ) AS candidate_rank
    FROM interactions i
    JOIN organizations o ON o.id = i.organization_id
    LEFT JOIN teams team ON team.id = o.team_id
    JOIN interaction_signatures isig ON isig.interaction_id = i.id
    JOIN workflow_templates t ON t.team_id IS NULL OR t.team_id = o.team_id
    LEFT JOIN template_signatures tsig ON tsig.template_id = t.id
)
UPDATE interactions i
SET workflow_template_id = c.template_id, workflow_version = 1
FROM candidates c
WHERE c.interaction_id = i.id AND c.candidate_rank = 1;

UPDATE interaction_stages st
SET template_stage_id = (
    SELECT ts.id
    FROM workflow_template_stages ts
    JOIN interactions i ON i.workflow_template_id = ts.template_id
    WHERE i.id = st.interaction_id AND ts.name = st.name
    ORDER BY ABS(ts.stage_order - st.stage_order), ts.stage_order
    LIMIT 1
);
