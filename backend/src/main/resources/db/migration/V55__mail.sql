-- Модуль почты (docs/mail.md). Таблицы создаются всегда, а пишутся только при MAIL_ENABLED=true.
-- Настройки подключения — одна строка; пароль SMTP шифруется ключом SOURCES_SETTINGS_KEY, как токены источников.
CREATE TABLE mail_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    mode VARCHAR(8) NOT NULL DEFAULT 'STUB' CHECK (mode IN ('STUB', 'SMTP')),
    host VARCHAR(255),
    port INTEGER CHECK (port BETWEEN 1 AND 65535),
    security VARCHAR(8) NOT NULL DEFAULT 'STARTTLS' CHECK (security IN ('NONE', 'STARTTLS', 'TLS')),
    username VARCHAR(255),
    password_encrypted VARCHAR(1000),
    from_address VARCHAR(254) NOT NULL DEFAULT 'crm@localhost',
    from_name VARCHAR(200) NOT NULL DEFAULT 'CRM ИТ Школы РТК',
    changed_at TIMESTAMP WITH TIME ZONE,
    checked_at TIMESTAMP WITH TIME ZONE,
    check_ok BOOLEAN,
    check_message VARCHAR(500),
    events_collected_until TIMESTAMP WITH TIME ZONE
);

INSERT INTO mail_settings (id) VALUES (1);

-- Очередь и журнал отправки в одной таблице: строка живёт от постановки до удаления по сроку хранения (30 дней).
-- event_key уникален — повтор того же события не ставит второе письмо. Адрес получателя-сотрудника берётся из
-- Keycloak в момент отправки, поэтому в строке хранится только маска; полный адрес хранится лишь у писем без
-- профиля (тестовое письмо, сведения субъекту ПДн). Вложение хранится до отправки и затем стирается.
CREATE TABLE mail_outbox (
    id UUID PRIMARY KEY,
    event_key VARCHAR(200) NOT NULL UNIQUE,
    kind VARCHAR(32) NOT NULL,
    profile_id UUID REFERENCES crm_user_profiles(id),
    recipient_email VARCHAR(254),
    recipient_masked VARCHAR(254),
    organization_id UUID,
    subject VARCHAR(300) NOT NULL,
    body_text TEXT NOT NULL,
    body_html TEXT NOT NULL,
    attachment_name VARCHAR(200),
    attachment_type VARCHAR(100),
    attachment_content BYTEA,
    status VARCHAR(10) NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'ERROR', 'DROPPED', 'CANCELLED')),
    transport VARCHAR(8) CHECK (transport IN ('STUB', 'SMTP')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(500),
    mime_message TEXT,
    CONSTRAINT mail_outbox_recipient_check CHECK (profile_id IS NOT NULL OR recipient_email IS NOT NULL)
);

CREATE INDEX mail_outbox_due_idx ON mail_outbox (status, next_attempt_at);
CREATE INDEX mail_outbox_created_idx ON mail_outbox (created_at);

-- Строка — тема, которую сотрудник отключил. По умолчанию всё включено, поэтому новому сотруднику строк не нужно.
CREATE TABLE mail_preferences (
    profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    topic VARCHAR(32) NOT NULL,
    PRIMARY KEY (profile_id, topic)
);

-- Приглашение по ссылке: хранится только SHA-256 токена. Пароль не хранится нигде — он создаётся в Keycloak в момент
-- открытия ссылки и показывается один раз.
CREATE TABLE mail_invitations (
    id UUID PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    redeemed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX mail_invitations_profile_idx ON mail_invitations (profile_id);
