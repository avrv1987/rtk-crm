-- Планы изменений, которые готовит ИИ-агент через MCP. Агент только сохраняет план, а исполняет его CRM, когда сотрудник
-- нажимает «Записать» на экране подтверждения. Таблица создаётся всегда, но пишется только при MCP_ENABLED=true.
-- Шаг и срок оба NOT NULL: команда комментария считает присланный nextStep заданным целиком, и пустое поле стёрло бы
-- текущее значение карточки.
CREATE TABLE agent_change_plans (
    id UUID PRIMARY KEY,
    requester_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    client_id VARCHAR(200),
    interaction_id UUID NOT NULL REFERENCES interactions(id),
    stage_id UUID NOT NULL,
    expected_version INTEGER NOT NULL,
    note_text VARCHAR(4000) NOT NULL,
    next_action VARCHAR(500) NOT NULL,
    next_action_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(16) NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by UUID REFERENCES crm_user_profiles(id),
    decided_at TIMESTAMP WITH TIME ZONE,
    command_id UUID REFERENCES command_idempotency_records(id),
    CONSTRAINT agent_change_plans_state_check
        CHECK (state IN ('PENDING', 'APPLIED', 'REJECTED', 'EXPIRED', 'STALE', 'FAILED')),
    CONSTRAINT agent_change_plans_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT agent_change_plans_applied_check CHECK (state <> 'APPLIED' OR command_id IS NOT NULL)
);

CREATE INDEX agent_change_plans_requester_idx ON agent_change_plans (requester_profile_id, state, created_at);
CREATE INDEX agent_change_plans_interaction_idx ON agent_change_plans (interaction_id);
