package ru.rtk.crm.max;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.access.CrmProfile;

@Repository
@MaxEnabled
public class MaxLinkRepository {
    private static final String LINK_COLUMNS = """
            profile_id, max_user_id, max_name, status, notify_digest, notify_assignment, notify_agreement,
            notify_site_request, confirmed_at, status_changed_at
            """;

    private final JdbcClient jdbcClient;

    public MaxLinkRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Link> findByProfile(UUID profileId) {
        return jdbcClient.sql("SELECT " + LINK_COLUMNS + " FROM max_links WHERE profile_id = :profileId")
                .param("profileId", profileId)
                .query(Link.class)
                .optional();
    }

    public Optional<Link> findByMaxUser(long maxUserId) {
        return jdbcClient.sql("SELECT " + LINK_COLUMNS + " FROM max_links WHERE max_user_id = :maxUserId")
                .param("maxUserId", maxUserId)
                .query(Link.class)
                .optional();
    }

    public List<AdminLink> findAll() {
        return jdbcClient.sql("""
                SELECT l.profile_id, p.display_name, p.role, l.max_name, l.status, l.confirmed_at, l.status_changed_at
                FROM max_links l
                JOIN crm_user_profiles p ON p.id = l.profile_id
                ORDER BY p.display_name, l.profile_id
                """)
                .query(AdminLink.class)
                .list();
    }

    public void replaceLink(UUID profileId, long maxUserId, String maxName, OffsetDateTime now) {
        jdbcClient.sql("DELETE FROM max_links WHERE profile_id = :profileId")
                .param("profileId", profileId)
                .update();
        jdbcClient.sql("""
                INSERT INTO max_links (profile_id, max_user_id, max_name, status, confirmed_at, status_changed_at)
                VALUES (:profileId, :maxUserId, :maxName, 'ACTIVE', :now, :now)
                """)
                .param("profileId", profileId)
                .param("maxUserId", maxUserId)
                .param("maxName", maxName)
                .param("now", now)
                .update();
    }

    public boolean deleteLink(UUID profileId) {
        return jdbcClient.sql("DELETE FROM max_links WHERE profile_id = :profileId")
                .param("profileId", profileId)
                .update() == 1;
    }

    // Событие старше последней смены статуса не применяется: повтор или опоздавшее bot_stopped не отключит
    // пользователя, который уже снова запустил бота.
    public boolean changeStatus(long maxUserId, String status, OffsetDateTime eventAt) {
        return jdbcClient.sql("""
                UPDATE max_links
                SET status = :status, status_changed_at = :eventAt
                WHERE max_user_id = :maxUserId AND status_changed_at < :eventAt
                """)
                .param("maxUserId", maxUserId)
                .param("status", status)
                .param("eventAt", eventAt)
                .update() == 1;
    }

    // Отказ MAX 403 при отправке тоже означает «бот остановлен»; время берётся текущее.
    public void stop(long maxUserId, OffsetDateTime now) {
        jdbcClient.sql("UPDATE max_links SET status = 'STOPPED', status_changed_at = :now WHERE max_user_id = :maxUserId AND status = 'ACTIVE'")
                .param("maxUserId", maxUserId)
                .param("now", now)
                .update();
    }

    public void savePreferences(UUID profileId, Preferences preferences) {
        jdbcClient.sql("""
                UPDATE max_links
                SET notify_digest = :digest, notify_assignment = :assignment, notify_agreement = :agreement,
                    notify_site_request = :siteRequest
                WHERE profile_id = :profileId
                """)
                .param("profileId", profileId)
                .param("digest", preferences.digest())
                .param("assignment", preferences.assignment())
                .param("agreement", preferences.agreement())
                .param("siteRequest", preferences.siteRequest())
                .update();
    }

    public void replaceCode(UUID profileId, String codeHash, OffsetDateTime expiresAt, OffsetDateTime now) {
        jdbcClient.sql("DELETE FROM max_link_codes WHERE profile_id = :profileId")
                .param("profileId", profileId)
                .update();
        jdbcClient.sql("""
                INSERT INTO max_link_codes (code_hash, profile_id, expires_at, created_at)
                VALUES (:codeHash, :profileId, :expiresAt, :now)
                """)
                .param("codeHash", codeHash)
                .param("profileId", profileId)
                .param("expiresAt", expiresAt)
                .param("now", now)
                .update();
    }

    public Optional<Code> findCodeByProfile(UUID profileId) {
        return jdbcClient.sql("""
                SELECT code_hash, profile_id, expires_at, found_max_user_id, found_name, found_at
                FROM max_link_codes
                WHERE profile_id = :profileId
                """)
                .param("profileId", profileId)
                .query(Code.class)
                .optional();
    }

    // Первый открывший ссылку аккаунт MAX закрепляется за кодом; повторный bot_started того же аккаунта ничего не
    // меняет, а чужой аккаунт с той же (пересланной) ссылкой получает отказ.
    public FoundResult markFound(String codeHash, long maxUserId, String name, OffsetDateTime now) {
        int updated = jdbcClient.sql("""
                UPDATE max_link_codes
                SET found_max_user_id = :maxUserId, found_name = :name, found_at = :now
                WHERE code_hash = :codeHash AND expires_at > :now AND found_max_user_id IS NULL
                """)
                .param("codeHash", codeHash)
                .param("maxUserId", maxUserId)
                .param("name", name)
                .param("now", now)
                .update();
        if (updated == 1) {
            return FoundResult.FOUND;
        }
        Optional<Long> found = jdbcClient.sql("""
                SELECT found_max_user_id FROM max_link_codes WHERE code_hash = :codeHash AND expires_at > :now
                """)
                .param("codeHash", codeHash)
                .param("now", now)
                .query(Long.class)
                .optional();
        return found.isPresent() && found.get() == maxUserId ? FoundResult.FOUND : FoundResult.INVALID;
    }

    public void deleteCode(UUID profileId) {
        jdbcClient.sql("DELETE FROM max_link_codes WHERE profile_id = :profileId")
                .param("profileId", profileId)
                .update();
    }

    public int deleteExpiredCodes(OffsetDateTime now) {
        return jdbcClient.sql("DELETE FROM max_link_codes WHERE expires_at < :now")
                .param("now", now)
                .update();
    }

    // Получатель для отправки и входа: активный профиль КАМ или руководителя без флага менеджера учебного процесса.
    // «Руководство», администратор, представитель вуза и менеджер учебного процесса канал MAX не получают.
    public Optional<CrmProfile> findChannelProfile(UUID profileId) {
        return jdbcClient.sql("""
                SELECT id, role, team_id, access_revision
                FROM crm_user_profiles
                WHERE id = :profileId AND active = TRUE AND role IN ('USER', 'LEADER') AND enrolment_operator = FALSE
                """)
                .param("profileId", profileId)
                .query(CrmProfile.class)
                .optional();
    }

    public Optional<String> findDisplayName(UUID profileId) {
        return jdbcClient.sql("SELECT display_name FROM crm_user_profiles WHERE id = :profileId")
                .param("profileId", profileId)
                .query(String.class)
                .optional();
    }

    public enum FoundResult {
        FOUND,
        INVALID
    }

    public record Link(
            UUID profileId,
            long maxUserId,
            String maxName,
            String status,
            boolean notifyDigest,
            boolean notifyAssignment,
            boolean notifyAgreement,
            boolean notifySiteRequest,
            OffsetDateTime confirmedAt,
            OffsetDateTime statusChangedAt
    ) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }

        public Preferences preferences() {
            return new Preferences(notifyDigest, notifyAssignment, notifyAgreement, notifySiteRequest);
        }

        public boolean allows(MaxMessageKind kind) {
            return switch (kind) {
                case REPLY -> true;
                case DIGEST -> notifyDigest;
                case ASSIGNMENT -> notifyAssignment;
                case AGREEMENT_CORRECTION, AGREEMENT_EXPIRY -> notifyAgreement;
                case SITE_REQUEST -> notifySiteRequest;
                // План ИИ-агента готовится по просьбе самого сотрудника: как ответ бота, отдельной настройкой не отключается.
                case AGENT_PLAN -> true;
            };
        }
    }

    public record Preferences(boolean digest, boolean assignment, boolean agreement, boolean siteRequest) {
    }

    public record Code(
            String codeHash,
            UUID profileId,
            OffsetDateTime expiresAt,
            Long foundMaxUserId,
            String foundName,
            OffsetDateTime foundAt
    ) {
    }

    public record AdminLink(
            UUID profileId,
            String displayName,
            String role,
            String maxName,
            String status,
            OffsetDateTime confirmedAt,
            OffsetDateTime statusChangedAt
    ) {
    }
}
