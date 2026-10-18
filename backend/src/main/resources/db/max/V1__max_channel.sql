-- Таблицы модуля MAX. Применяются отдельным Flyway модуля (история в flyway_schema_history_max) и только при
-- MAX_ENABLED=true; основной Flyway каталог db/max не читает.

-- Настройки бота: одна строка. Токен и секрет вебхука хранятся зашифрованными (AES-GCM, ключ APP_SOURCES_SETTINGS_KEY).
CREATE TABLE max_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    token_encrypted VARCHAR(2000),
    webhook_secret_encrypted VARCHAR(2000),
    bot_name VARCHAR(100),
    mini_app_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    changed_at TIMESTAMP WITH TIME ZONE,
    subscribed_at TIMESTAMP WITH TIME ZONE,
    subscription_checked_at TIMESTAMP WITH TIME ZONE,
    subscription_ok BOOLEAN,
    subscription_error VARCHAR(500),
    last_event_at TIMESTAMP WITH TIME ZONE,
    events_collected_until TIMESTAMP WITH TIME ZONE
);

INSERT INTO max_settings (id, mini_app_enabled) VALUES (1, FALSE);

-- Подтверждённая связь профиля CRM с аккаунтом MAX и настройки уведомлений. Один аккаунт MAX — один профиль.
-- status_changed_at хранит время события MAX, по которому сменился статус: событие старше не откатывает состояние.
CREATE TABLE max_links (
    profile_id UUID PRIMARY KEY REFERENCES crm_user_profiles(id),
    max_user_id BIGINT NOT NULL,
    max_name VARCHAR(200),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'STOPPED')),
    notify_digest BOOLEAN NOT NULL DEFAULT TRUE,
    notify_assignment BOOLEAN NOT NULL DEFAULT TRUE,
    notify_agreement BOOLEAN NOT NULL DEFAULT TRUE,
    notify_site_request BOOLEAN NOT NULL DEFAULT TRUE,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status_changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT max_links_user_key UNIQUE (max_user_id)
);

-- Одноразовый код привязки: в базе только SHA-256. Аккаунт MAX, открывший ссылку, записывается как найденный и
-- становится связью только после подтверждения в CRM тем же сотрудником.
CREATE TABLE max_link_codes (
    code_hash CHAR(64) PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    found_max_user_id BIGINT,
    found_name VARCHAR(200),
    found_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT max_link_codes_profile_key UNIQUE (profile_id)
);

-- Входящие события вебхука. event_key строится по полям события и защищает от повторной доставки.
CREATE TABLE max_inbox (
    id UUID PRIMARY KEY,
    event_key VARCHAR(200) NOT NULL,
    update_type VARCHAR(40) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RECEIVED', 'DONE', 'ERROR')),
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT max_inbox_event_key UNIQUE (event_key)
);

CREATE INDEX max_inbox_due_idx ON max_inbox (status, received_at);

-- Исходящие сообщения. Перед отправкой получатель и видимость вуза проверяются заново; устаревшее не отправляется.
CREATE TABLE max_outbox (
    id UUID PRIMARY KEY,
    event_key VARCHAR(200) NOT NULL,
    profile_id UUID REFERENCES crm_user_profiles(id),
    max_user_id BIGINT NOT NULL,
    kind VARCHAR(24) NOT NULL,
    organization_id UUID,
    body TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'ERROR', 'DROPPED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error VARCHAR(500),
    sent_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT max_outbox_event_key UNIQUE (event_key)
);

CREATE INDEX max_outbox_due_idx ON max_outbox (status, next_attempt_at);
