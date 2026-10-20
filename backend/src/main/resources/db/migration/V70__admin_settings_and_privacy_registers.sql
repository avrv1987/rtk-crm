-- Настройки системы, которые администратор меняет на экране вместо переменных окружения. Строка есть только у
-- изменённой настройки: без неё действует значение конфигурации сервера, сброс удаляет строку.
CREATE TABLE system_settings (
    setting_key VARCHAR(64) PRIMARY KEY,
    setting_value INTEGER NOT NULL,
    updated_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Норматив этапа в днях: «долго на этапе» берёт его, а без него — общий порог. Хранится только у этапа шаблона:
-- этап карточки находит его по template_stage_id, поэтому правка норматива сразу действует на идущие работы.
ALTER TABLE workflow_template_stages ADD COLUMN norm_days INTEGER;
ALTER TABLE workflow_template_stages ADD CONSTRAINT workflow_template_stages_norm_days_check
    CHECK (norm_days BETWEEN 1 AND 3650);

-- Журнал обращений субъектов ПДн. Исполняет администратор (решение владельца 21.10); исполнение — выгрузка или
-- обезличивание — связывается записью журнала аудита, которая остаётся основанием и после правки обращения.
CREATE TABLE personal_data_requests (
    id UUID PRIMARY KEY,
    received_on DATE NOT NULL,
    subject_name VARCHAR(300) NOT NULL,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('INFORMATION', 'RECTIFICATION', 'DELETION', 'CONSENT_WITHDRAWAL')),
    channel VARCHAR(200) NOT NULL,
    due_on DATE NOT NULL,
    assignee_id UUID REFERENCES crm_user_profiles(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('NEW', 'IN_PROGRESS', 'DONE', 'REJECTED')),
    completed_on DATE,
    audit_event_id UUID REFERENCES audit_events(id) ON DELETE SET NULL,
    note VARCHAR(1000),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT personal_data_requests_completed_check CHECK (
        (status IN ('DONE', 'REJECTED')) = (completed_on IS NOT NULL)
    )
);

CREATE INDEX personal_data_requests_open_idx ON personal_data_requests (status, due_on);

-- Учёт согласий — включаемая функция (настройки CONSENTS_CONTACTS и CONSENTS_LEARNERS). Ссылка на субъект без
-- внешнего ключа: контакты и анкеты слушателей лежат в разных таблицах. ФИО в записи согласия нет, поэтому
-- обезличивание субъекта её не трогает: дата, основание и отзыв остаются подтверждением законности обработки.
CREATE TABLE personal_data_consents (
    id UUID PRIMARY KEY,
    subject_type VARCHAR(16) NOT NULL CHECK (subject_type IN ('CONTACT', 'LEARNER')),
    subject_id UUID NOT NULL,
    given_on DATE NOT NULL,
    basis VARCHAR(300) NOT NULL,
    document VARCHAR(300),
    withdrawn_on DATE,
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT personal_data_consents_withdrawn_check CHECK (withdrawn_on IS NULL OR withdrawn_on >= given_on)
);

CREATE INDEX personal_data_consents_subject_idx ON personal_data_consents (subject_type, subject_id);
