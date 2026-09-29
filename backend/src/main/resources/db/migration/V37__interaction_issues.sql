CREATE TABLE interaction_issues (
    id UUID PRIMARY KEY,
    interaction_id UUID NOT NULL REFERENCES interactions(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('PROBLEM', 'RISK')),
    description VARCHAR(1000) NOT NULL,
    risk_level VARCHAR(16) CHECK (risk_level IN ('MEDIUM', 'HIGH')),
    responsible_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    due_on DATE,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    resolution VARCHAR(1000),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_by UUID REFERENCES crm_user_profiles(id),
    resolved_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT interaction_issues_level_check CHECK ((kind = 'RISK') = (risk_level IS NOT NULL)),
    CONSTRAINT interaction_issues_resolution_check CHECK (
        (status = 'OPEN' AND resolution IS NULL AND resolved_by IS NULL AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND resolution IS NOT NULL AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL)
    )
);

CREATE INDEX interaction_issues_open_idx ON interaction_issues (interaction_id, kind) WHERE status = 'OPEN';
CREATE INDEX interaction_issues_responsible_idx ON interaction_issues (responsible_profile_id, status);

WITH marked AS (
    SELECT i.id AS interaction_id, i.problem, i.risk_level, i.risk_reason,
           COALESCE(last_mark.actor_profile_id, i.created_by) AS created_by,
           COALESCE(last_mark.occurred_at, i.updated_at) AS created_at,
           COALESCE(o.owner_manager_id, last_mark.actor_profile_id, i.created_by) AS responsible_profile_id
    FROM interactions i
    JOIN organizations o ON o.id = i.organization_id
    LEFT JOIN LATERAL (
        SELECT e.actor_profile_id, e.occurred_at
        FROM interaction_events e
        WHERE e.interaction_id = i.id AND e.type = 'DETAILS_UPDATED'
        ORDER BY e.occurred_at DESC, e.version DESC
        LIMIT 1
    ) last_mark ON TRUE
    WHERE i.problem IS NOT NULL OR i.risk_level IS NOT NULL
)
INSERT INTO interaction_issues (
    id, interaction_id, kind, description, risk_level, responsible_profile_id, created_by, created_at
)
SELECT gen_random_uuid(), interaction_id, 'PROBLEM', problem, NULL, responsible_profile_id, created_by, created_at
FROM marked
WHERE problem IS NOT NULL
UNION ALL
SELECT gen_random_uuid(), interaction_id, 'RISK', risk_reason, risk_level, responsible_profile_id, created_by, created_at
FROM marked
WHERE risk_level IS NOT NULL;

ALTER TABLE interactions DROP CONSTRAINT interactions_risk_reason_check;
ALTER TABLE interactions DROP COLUMN problem;
ALTER TABLE interactions DROP COLUMN risk_level;
ALTER TABLE interactions DROP COLUMN risk_reason;
