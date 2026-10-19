-- Виды плана ИИ-агента: кроме итога со следующим шагом — переход этапа, проблема или риск, решение проблемы, пауза
-- и возобновление. Одна таблица: один экран подтверждения, один жизненный цикл, одна квота и один учёт ПДн.
-- note_text хранит текст, который станет комментарием команды: обоснование перехода, описание или решение проблемы,
-- причину паузы. Старые строки получают NOTE_UPDATE значением по умолчанию.
ALTER TABLE agent_change_plans ADD COLUMN kind VARCHAR(24) DEFAULT 'NOTE_UPDATE' NOT NULL;
ALTER TABLE agent_change_plans ADD COLUMN to_stage_id UUID;
ALTER TABLE agent_change_plans ADD COLUMN issue_id UUID REFERENCES interaction_issues(id);
ALTER TABLE agent_change_plans ADD COLUMN issue_kind VARCHAR(16);
ALTER TABLE agent_change_plans ADD COLUMN risk_level VARCHAR(16);
ALTER TABLE agent_change_plans ADD COLUMN responsible_profile_id UUID REFERENCES crm_user_profiles(id);
ALTER TABLE agent_change_plans ADD COLUMN due_on DATE;
ALTER TABLE agent_change_plans ADD COLUMN work_status VARCHAR(16);
ALTER TABLE agent_change_plans ADD COLUMN resume_on DATE;
-- До десяти событий этой же карточки через запятую: CRM рисует их на экране из истории, а не со слов агента.
ALTER TABLE agent_change_plans ADD COLUMN evidence_event_ids VARCHAR(400);

-- NOT NULL снимается только ради других видов. Защита V46 остаётся проверкой: у NOTE_UPDATE все три поля обязательны,
-- а шаг и срок у любого вида — оба или ни одного, иначе неполный шаг стёр бы срок карточки.
ALTER TABLE agent_change_plans ALTER COLUMN note_text DROP NOT NULL;
ALTER TABLE agent_change_plans ALTER COLUMN next_action DROP NOT NULL;
ALTER TABLE agent_change_plans ALTER COLUMN next_action_at DROP NOT NULL;

ALTER TABLE agent_change_plans ADD CONSTRAINT agent_change_plans_kind_check
    CHECK (kind IN ('NOTE_UPDATE', 'STAGE_TRANSITION', 'ISSUE_CREATE', 'ISSUE_RESOLVE', 'STATUS_CHANGE'));
ALTER TABLE agent_change_plans ADD CONSTRAINT agent_change_plans_next_step_check
    CHECK ((next_action IS NULL AND next_action_at IS NULL) OR (next_action IS NOT NULL AND next_action_at IS NOT NULL));
ALTER TABLE agent_change_plans ADD CONSTRAINT agent_change_plans_note_update_check
    CHECK (kind <> 'NOTE_UPDATE' OR (note_text IS NOT NULL AND next_action IS NOT NULL AND next_action_at IS NOT NULL));
ALTER TABLE agent_change_plans ADD CONSTRAINT agent_change_plans_transition_check
    CHECK (kind <> 'STAGE_TRANSITION' OR (to_stage_id IS NOT NULL AND note_text IS NOT NULL));
ALTER TABLE agent_change_plans ADD CONSTRAINT agent_change_plans_issue_create_check
    CHECK (kind <> 'ISSUE_CREATE' OR (issue_kind IN ('PROBLEM', 'RISK') AND note_text IS NOT NULL
        AND responsible_profile_id IS NOT NULL AND due_on IS NOT NULL AND (issue_kind <> 'RISK' OR risk_level IS NOT NULL)));
ALTER TABLE agent_change_plans ADD CONSTRAINT agent_change_plans_issue_resolve_check
    CHECK (kind <> 'ISSUE_RESOLVE' OR (issue_id IS NOT NULL AND note_text IS NOT NULL));
-- COMPLETED агенту недоступен: завершение взаимодействия — решение человека.
ALTER TABLE agent_change_plans ADD CONSTRAINT agent_change_plans_status_check
    CHECK (kind <> 'STATUS_CHANGE' OR (note_text IS NOT NULL AND (work_status = 'ACTIVE'
        OR (work_status = 'PAUSED' AND resume_on IS NOT NULL))));
