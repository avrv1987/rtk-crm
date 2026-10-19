package ru.rtk.crm.mail;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@MailEnabled
public class MailRepository {
    static final int MAX_ATTEMPTS = 5;
    static final int ERROR_LIMIT = 500;

    private final JdbcClient jdbcClient;

    public MailRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Settings settings() {
        return jdbcClient.sql("""
                SELECT mode, host, port, security, username, password_encrypted, from_address, from_name, changed_at,
                       checked_at, check_ok, check_message, events_collected_until
                FROM mail_settings
                WHERE id = 1
                """)
                .query((rs, row) -> new Settings(
                        MailMode.valueOf(rs.getString("mode")),
                        rs.getString("host"),
                        (Integer) rs.getObject("port"),
                        MailSecurity.valueOf(rs.getString("security")),
                        rs.getString("username"),
                        rs.getString("password_encrypted"),
                        rs.getString("from_address"),
                        rs.getString("from_name"),
                        rs.getObject("changed_at", OffsetDateTime.class),
                        rs.getObject("checked_at", OffsetDateTime.class),
                        (Boolean) rs.getObject("check_ok"),
                        rs.getString("check_message"),
                        rs.getObject("events_collected_until", OffsetDateTime.class)
                ))
                .single();
    }

    public void saveSettings(Settings settings, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE mail_settings
                SET mode = :mode, host = :host, port = :port, security = :security, username = :username,
                    password_encrypted = :password, from_address = :fromAddress, from_name = :fromName, changed_at = :now,
                    checked_at = CAST(NULL AS TIMESTAMP WITH TIME ZONE), check_ok = CAST(NULL AS BOOLEAN),
                    check_message = CAST(NULL AS VARCHAR(500))
                WHERE id = 1
                """)
                .param("mode", settings.mode().name())
                .param("host", settings.host())
                .param("port", settings.port())
                .param("security", settings.security().name())
                .param("username", settings.username())
                .param("password", settings.passwordEncrypted())
                .param("fromAddress", settings.fromAddress())
                .param("fromName", settings.fromName())
                .param("now", now)
                .update();
    }

    public void saveCheck(boolean ok, String message, OffsetDateTime now) {
        jdbcClient.sql("UPDATE mail_settings SET checked_at = :now, check_ok = :ok, check_message = :message WHERE id = 1")
                .param("now", now)
                .param("ok", ok)
                .param("message", truncate(message))
                .update();
    }

    public void saveCollectedUntil(OffsetDateTime until) {
        jdbcClient.sql("UPDATE mail_settings SET events_collected_until = :until WHERE id = 1")
                .param("until", until)
                .update();
    }

    public boolean enqueue(NewLetter letter, OffsetDateTime now, OffsetDateTime expiresAt) {
        return jdbcClient.sql("""
                INSERT INTO mail_outbox (id, event_key, kind, profile_id, recipient_email, recipient_masked, organization_id,
                                         subject, body_text, body_html, attachment_name, attachment_type, attachment_content,
                                         status, attempts, next_attempt_at, expires_at, created_at)
                VALUES (:id, :eventKey, :kind, :profileId, :email, :masked, :organizationId, :subject, :text, :html,
                        :attachmentName, :attachmentType, :attachment, 'QUEUED', 0, :now, :expiresAt, :now)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("eventKey", letter.eventKey().length() > 200 ? MailCodes.sha256(letter.eventKey()) : letter.eventKey())
                .param("kind", letter.kind().name())
                .param("profileId", letter.profileId())
                .param("email", letter.recipientEmail())
                .param("masked", letter.recipientEmail() == null ? null : MailCodes.mask(letter.recipientEmail()))
                .param("organizationId", letter.organizationId())
                .param("subject", letter.subject())
                .param("text", letter.text())
                .param("html", letter.html())
                .param("attachmentName", letter.attachmentName())
                .param("attachmentType", letter.attachmentType())
                .param("attachment", letter.attachment())
                .param("now", now)
                .param("expiresAt", expiresAt)
                .update() == 1;
    }

    public List<OutboxRow> findDue(OffsetDateTime now, int limit) {
        return jdbcClient.sql(SELECT_ROW + " WHERE status = 'QUEUED' AND next_attempt_at <= :now ORDER BY next_attempt_at, created_at, id LIMIT :limit")
                .param("now", now)
                .param("limit", limit)
                .query(this::outboxRow)
                .list();
    }

    public Optional<OutboxRow> find(UUID id) {
        return jdbcClient.sql(SELECT_ROW + " WHERE id = :id")
                .param("id", id)
                .query(this::outboxRow)
                .optional();
    }

    public Optional<OutboxRow> findByKey(String eventKey) {
        return jdbcClient.sql(SELECT_ROW + " WHERE event_key = :eventKey")
                .param("eventKey", eventKey)
                .query(this::outboxRow)
                .optional();
    }

    private static final String SELECT_ROW = """
            SELECT id, event_key, kind, profile_id, recipient_email, recipient_masked, organization_id, subject, body_text, body_html,
                   attachment_name, attachment_type, attachment_content, status, transport, attempts, created_at, sent_at,
                   expires_at, last_error, mime_message
            FROM mail_outbox
            """;

    private OutboxRow outboxRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String transport = rs.getString("transport");
        return new OutboxRow(
                rs.getObject("id", UUID.class),
                rs.getString("event_key"),
                MailKind.valueOf(rs.getString("kind")),
                rs.getObject("profile_id", UUID.class),
                rs.getString("recipient_email"),
                rs.getString("recipient_masked"),
                rs.getObject("organization_id", UUID.class),
                rs.getString("subject"),
                rs.getString("body_text"),
                rs.getString("body_html"),
                rs.getString("attachment_name"),
                rs.getString("attachment_type"),
                rs.getBytes("attachment_content"),
                rs.getString("status"),
                transport == null ? null : MailMode.valueOf(transport),
                rs.getInt("attempts"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("sent_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("last_error"),
                rs.getString("mime_message")
        );
    }

    // Вложение после отправки стирается: копия сведений субъекту ПДн не должна лежать в журнале месяц.
    public void markSent(UUID id, OffsetDateTime now, MailMode transport, String masked, String mime) {
        jdbcClient.sql("""
                UPDATE mail_outbox
                SET status = 'SENT', sent_at = :now, transport = :transport, attempts = attempts + 1,
                    recipient_masked = :masked, mime_message = :mime, attachment_content = CAST(NULL AS BYTEA),
                    recipient_email = CASE WHEN profile_id IS NULL THEN recipient_email ELSE CAST(NULL AS VARCHAR(254)) END,
                    last_error = CAST(NULL AS VARCHAR(500))
                WHERE id = :id
                """)
                .param("id", id)
                .param("now", now)
                .param("transport", transport.name())
                .param("masked", masked)
                .param("mime", mime)
                .update();
    }

    public void markDropped(UUID id, String reason) {
        jdbcClient.sql("UPDATE mail_outbox SET status = 'DROPPED', last_error = :reason, attachment_content = CAST(NULL AS BYTEA) WHERE id = :id")
                .param("id", id)
                .param("reason", truncate(reason))
                .update();
    }

    public void markRetry(UUID id, OffsetDateTime nextAttemptAt, String error) {
        jdbcClient.sql("""
                UPDATE mail_outbox
                SET attempts = attempts + 1, next_attempt_at = :next, last_error = :error,
                    status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'ERROR' ELSE 'QUEUED' END
                WHERE id = :id
                """)
                .param("id", id)
                .param("next", nextAttemptAt)
                .param("error", truncate(error))
                .param("maxAttempts", MAX_ATTEMPTS)
                .update();
    }

    // Повтор вручную — только для ещё не устаревших писем: просроченное отправщик всё равно сбросит.
    public boolean retry(UUID id, OffsetDateTime now) {
        // Время усечено до микросекунд: база округляет остаток вверх, а часы Windows идут шагами около миллисекунды,
        // и проход отправщика в тот же шаг часов видел бы письмо «из будущего» и пропускал его.
        return jdbcClient.sql("""
                UPDATE mail_outbox
                SET status = 'QUEUED', attempts = 0, next_attempt_at = :now
                WHERE id = :id AND status = 'ERROR' AND expires_at > :now
                """)
                .param("id", id)
                .param("now", now.truncatedTo(ChronoUnit.MICROS))
                .update() == 1;
    }

    public int requeueErrors(OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE mail_outbox
                SET status = 'QUEUED', attempts = 0, next_attempt_at = :now
                WHERE status = 'ERROR' AND expires_at > :now
                """)
                .param("now", now.truncatedTo(ChronoUnit.MICROS))
                .update();
    }

    public boolean cancel(UUID id) {
        return jdbcClient.sql("""
                UPDATE mail_outbox
                SET status = 'CANCELLED', last_error = 'отменено администратором', attachment_content = CAST(NULL AS BYTEA)
                WHERE id = :id AND status IN ('QUEUED', 'ERROR')
                """)
                .param("id", id)
                .update() == 1;
    }

    private static final String SELECT_JOURNAL = """
            SELECT id, kind, recipient_masked, profile_id, subject, status, transport, attempts, created_at, sent_at, last_error
            FROM mail_outbox
            """;

    public List<JournalRow> journal(String status, int limit, int offset) {
        return jdbcClient.sql(SELECT_JOURNAL + " WHERE (:status = '' OR status = :status) ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset")
                .param("status", status == null ? "" : status)
                .param("limit", limit)
                .param("offset", offset)
                .query(this::mapJournal)
                .list();
    }

    public Optional<JournalRow> journalRow(UUID id) {
        return jdbcClient.sql(SELECT_JOURNAL + " WHERE id = :id")
                .param("id", id)
                .query(this::mapJournal)
                .optional();
    }

    private JournalRow mapJournal(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String transport = rs.getString("transport");
        MailKind kind = MailKind.valueOf(rs.getString("kind"));
        return new JournalRow(
                rs.getObject("id", UUID.class),
                kind,
                kind.title(),
                rs.getString("recipient_masked"),
                rs.getObject("profile_id", UUID.class) != null,
                rs.getString("subject"),
                rs.getString("status"),
                transport == null ? null : MailMode.valueOf(transport),
                rs.getInt("attempts"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("sent_at", OffsetDateTime.class),
                rs.getString("last_error")
        );
    }

    public long journalTotal(String status) {
        return jdbcClient.sql("SELECT COUNT(*) FROM mail_outbox WHERE (:status = '' OR status = :status)")
                .param("status", status == null ? "" : status)
                .query(Long.class)
                .single();
    }

    public QueueCounts counts(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT
                    (SELECT COUNT(*) FROM mail_outbox WHERE status = 'QUEUED') AS queued,
                    (SELECT COUNT(*) FROM mail_outbox WHERE status = 'ERROR') AS errors,
                    (SELECT COUNT(*) FROM mail_outbox WHERE status = 'SENT' AND sent_at >= :since) AS sent,
                    (SELECT COUNT(*) FROM mail_outbox WHERE status = 'DROPPED' AND created_at >= :since) AS dropped,
                    (SELECT COUNT(*) FROM mail_outbox WHERE status = 'CANCELLED' AND created_at >= :since) AS cancelled
                """)
                .param("since", since)
                .query(QueueCounts.class)
                .single();
    }

    public int deleteOutboxBefore(OffsetDateTime cutoff) {
        return jdbcClient.sql("DELETE FROM mail_outbox WHERE created_at < :cutoff AND status <> 'QUEUED'")
                .param("cutoff", cutoff)
                .update();
    }

    public Set<MailTopic> disabledTopics(UUID profileId) {
        Set<MailTopic> topics = new HashSet<>();
        for (String topic : jdbcClient.sql("SELECT topic FROM mail_preferences WHERE profile_id = :profileId")
                .param("profileId", profileId)
                .query(String.class)
                .list()) {
            topics.add(MailTopic.valueOf(topic));
        }
        return topics;
    }

    public void replaceDisabledTopics(UUID profileId, Set<MailTopic> topics) {
        jdbcClient.sql("DELETE FROM mail_preferences WHERE profile_id = :profileId").param("profileId", profileId).update();
        for (MailTopic topic : topics) {
            jdbcClient.sql("INSERT INTO mail_preferences (profile_id, topic) VALUES (:profileId, :topic)")
                    .param("profileId", profileId)
                    .param("topic", topic.name())
                    .update();
        }
    }

    public void insertInvitation(UUID id, String tokenHash, UUID profileId, OffsetDateTime now, OffsetDateTime expiresAt) {
        jdbcClient.sql("""
                INSERT INTO mail_invitations (id, token_hash, profile_id, created_at, expires_at)
                VALUES (:id, :hash, :profileId, :now, :expiresAt)
                """)
                .param("id", id)
                .param("hash", tokenHash)
                .param("profileId", profileId)
                .param("now", now)
                .param("expiresAt", expiresAt)
                .update();
    }

    public void replaceInvitationToken(UUID id, String tokenHash) {
        jdbcClient.sql("UPDATE mail_invitations SET token_hash = :hash WHERE id = :id")
                .param("id", id)
                .param("hash", tokenHash)
                .update();
    }

    // Новое приглашение гасит прежние неиспользованные: работает только последняя ссылка.
    public void closeOpenInvitations(UUID profileId, OffsetDateTime now) {
        // Усечение — как в retry: иначе закрытая ссылка, открытая в тот же шаг часов, ещё считалась бы действующей.
        jdbcClient.sql("UPDATE mail_invitations SET expires_at = :now WHERE profile_id = :profileId AND redeemed_at IS NULL AND expires_at > :now")
                .param("profileId", profileId)
                .param("now", now.truncatedTo(ChronoUnit.MICROS))
                .update();
    }

    public Optional<Invitation> lockInvitation(String tokenHash) {
        return jdbcClient.sql("""
                SELECT id, profile_id, expires_at, redeemed_at
                FROM mail_invitations
                WHERE token_hash = :hash
                FOR UPDATE
                """)
                .param("hash", tokenHash)
                .query(Invitation.class)
                .optional();
    }

    public void markRedeemed(UUID id, OffsetDateTime now) {
        jdbcClient.sql("UPDATE mail_invitations SET redeemed_at = :now WHERE id = :id")
                .param("id", id)
                .param("now", now)
                .update();
    }

    public boolean invitationOpen(UUID invitationId, OffsetDateTime now) {
        return jdbcClient.sql("SELECT COUNT(*) FROM mail_invitations WHERE id = :id AND redeemed_at IS NULL AND expires_at > :now")
                .param("id", invitationId)
                .param("now", now)
                .query(Long.class)
                .single() > 0;
    }

    public int deleteInvitationsBefore(OffsetDateTime cutoff) {
        return jdbcClient.sql("DELETE FROM mail_invitations WHERE expires_at < :cutoff")
                .param("cutoff", cutoff)
                .update();
    }

    public Optional<Recipient> recipient(UUID profileId) {
        return jdbcClient.sql("""
                SELECT id, role, team_id, access_revision, active, pending_activation, enrolment_operator,
                       partner_organization_id, login
                FROM crm_user_profiles
                WHERE id = :profileId
                """)
                .param("profileId", profileId)
                .query(Recipient.class)
                .optional();
    }

    static String truncate(String value) {
        return value == null || value.length() <= ERROR_LIMIT ? value : value.substring(0, ERROR_LIMIT);
    }

    public enum MailMode {
        STUB,
        SMTP
    }

    public enum MailSecurity {
        NONE,
        STARTTLS,
        TLS
    }

    public record Settings(
            MailMode mode,
            String host,
            Integer port,
            MailSecurity security,
            String username,
            String passwordEncrypted,
            String fromAddress,
            String fromName,
            OffsetDateTime changedAt,
            OffsetDateTime checkedAt,
            Boolean checkOk,
            String checkMessage,
            OffsetDateTime eventsCollectedUntil
    ) {
    }

    public record NewLetter(
            String eventKey,
            MailKind kind,
            UUID profileId,
            String recipientEmail,
            UUID organizationId,
            String subject,
            String text,
            String html,
            String attachmentName,
            String attachmentType,
            byte[] attachment
    ) {
    }

    public record OutboxRow(
            UUID id,
            String eventKey,
            MailKind kind,
            UUID profileId,
            String recipientEmail,
            String recipientMasked,
            UUID organizationId,
            String subject,
            String bodyText,
            String bodyHtml,
            String attachmentName,
            String attachmentType,
            byte[] attachment,
            String status,
            MailMode transport,
            int attempts,
            OffsetDateTime createdAt,
            OffsetDateTime sentAt,
            OffsetDateTime expiresAt,
            String lastError,
            String mime
    ) {
    }

    public record JournalRow(
            UUID id,
            MailKind kind,
            String kindTitle,
            String recipient,
            boolean employee,
            String subject,
            String status,
            MailMode transport,
            int attempts,
            OffsetDateTime createdAt,
            OffsetDateTime sentAt,
            String error
    ) {
    }

    public record QueueCounts(long queued, long errors, long sent, long dropped, long cancelled) {
    }

    public record Invitation(UUID id, UUID profileId, OffsetDateTime expiresAt, OffsetDateTime redeemedAt) {
    }

    public record Recipient(
            UUID id,
            String role,
            UUID teamId,
            int accessRevision,
            boolean active,
            boolean pendingActivation,
            boolean enrolmentOperator,
            UUID partnerOrganizationId,
            String login
    ) {
    }
}
