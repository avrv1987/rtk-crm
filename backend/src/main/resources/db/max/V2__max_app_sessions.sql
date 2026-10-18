-- Сеансы мини-приложения MAX. Токен живёт только в памяти страницы, здесь — его SHA-256. access_revision фиксирует
-- ревизию доступа профиля при входе: смена роли, команды или блокировка делают сеанс недействительным.
CREATE TABLE max_sessions (
    token_hash CHAR(64) PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    max_user_id BIGINT NOT NULL,
    access_revision INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX max_sessions_profile_idx ON max_sessions (profile_id);
