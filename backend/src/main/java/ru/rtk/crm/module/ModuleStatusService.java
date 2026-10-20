package ru.rtk.crm.module;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditEntry;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.mcp.McpProperties;
import ru.rtk.crm.source.MoodleWriteProperties;

// Экран состояния модулей (решение владельца 21.10 №10): что включено, по какой переменной и исправно ли.
// Включение по-прежнему за администратором сервера — экран только показывает. Исправность берётся из того, что
// модули уже записывают: очереди писем и сообщений MAX и журнал аудита; ошибки — за последние 7 дней.
@Service
public class ModuleStatusService {
    private static final int ERROR_DAYS = 7;

    private final JdbcClient jdbcClient;
    private final ModuleProperties modules;
    private final McpProperties mcp;
    private final MoodleWriteProperties moodleWrite;
    private final AuditJournalRepository auditJournalRepository;

    public ModuleStatusService(JdbcClient jdbcClient, ModuleProperties modules, McpProperties mcp, MoodleWriteProperties moodleWrite,
                               AuditJournalRepository auditJournalRepository) {
        this.jdbcClient = jdbcClient;
        this.modules = modules;
        this.mcp = mcp;
        this.moodleWrite = moodleWrite;
        this.auditJournalRepository = auditJournalRepository;
    }

    @Transactional(readOnly = true)
    public List<ModuleStatus> list(CrmProfile profile) {
        AdminAuthorization.requireAdmin(profile);
        OffsetDateTime since = OffsetDateTime.now().minusDays(ERROR_DAYS);
        List<ModuleStatus> result = new ArrayList<>();
        // Таблицы MAX создаёт только включённый модуль (свой Flyway), поэтому очередь читается лишь при включённом.
        result.add(modules.maxEnabled()
                ? queue("MAX", "Бот и мини-приложение MAX", "MAX_ENABLED", "max_outbox", since)
                : off("MAX", "Бот и мини-приложение MAX", "MAX_ENABLED"));
        result.add(fromJournal("MCP", "ИИ-агенты (MCP)", "MCP_ENABLED", mcp.enabled(), AuditAction.AGENT_TOOL_CALLED,
                AuditAction.AGENT_PLAN_FAILED, since));
        result.add(modules.mailEnabled()
                ? mailQueue(since)
                : off("MAIL", "Почта", "MAIL_ENABLED"));
        result.add(fromJournal("MOODLE_WRITE", "Запись в Moodle", "MOODLE_WRITE_ENABLED", moodleWrite.enabled(),
                AuditAction.LMS_API_TRANSFER, null, since));
        result.add(fromJournal("ENROLMENT", "Слушатели", "MODULE_ENROLMENT_ENABLED", modules.enrolmentEnabled(),
                AuditAction.LEARNERS_SYNCED, null, since));
        result.add(fromJournal("PARTNER", "Кабинет вуза", "MODULE_PARTNER_ENABLED", modules.partnerEnabled(),
                AuditAction.PARTNER_RESPONSE_SUBMITTED, null, since));
        return result;
    }

    private static ModuleStatus off(String code, String title, String variable) {
        return new ModuleStatus(code, title, variable, false, null, null, null, null);
    }

    // Очередь отправки: последняя доставка — успех, сообщения со статусом ERROR — ошибки.
    private ModuleStatus queue(String code, String title, String variable, String table, OffsetDateTime since) {
        OffsetDateTime lastSent = jdbcClient.sql("SELECT MAX(sent_at) FROM " + table + " WHERE status = 'SENT'")
                .query(OffsetDateTime.class).optional().orElse(null);
        return queueStatus(code, title, variable, table, since, lastSent, lastSent == null ? null : "последняя доставка");
    }

    // Почта в режиме «Без отправки» помечает письмо отправленным, но наружу оно не уходит: подпись берётся
    // по транспорту последнего письма, чтобы не называть запись в журнал доставкой.
    private ModuleStatus mailQueue(OffsetDateTime since) {
        Optional<Map.Entry<OffsetDateTime, String>> last = jdbcClient.sql(
                        "SELECT sent_at, transport FROM mail_outbox WHERE status = 'SENT' AND sent_at IS NOT NULL ORDER BY sent_at DESC LIMIT 1")
                .query((resultSet, rowNumber) -> Map.entry(resultSet.getObject("sent_at", OffsetDateTime.class),
                        Objects.requireNonNullElse(resultSet.getString("transport"), "")))
                .optional();
        String label = last.map(entry -> "STUB".equals(entry.getValue())
                ? "письмо записано в журнал без отправки (режим «Без отправки»)" : "последняя доставка").orElse(null);
        return queueStatus("MAIL", "Почта", "MAIL_ENABLED", "mail_outbox", since, last.map(Map.Entry::getKey).orElse(null), label);
    }

    private ModuleStatus queueStatus(String code, String title, String variable, String table, OffsetDateTime since,
                                     OffsetDateTime lastSent, String lastSentLabel) {
        long errors = jdbcClient.sql("SELECT COUNT(*) FROM " + table + " WHERE status = 'ERROR' AND created_at >= :since")
                .param("since", since).query(Long.class).single();
        String lastError = jdbcClient.sql("SELECT last_error FROM " + table + " WHERE status = 'ERROR' AND last_error IS NOT NULL"
                        + " ORDER BY created_at DESC LIMIT 1")
                .query(String.class).optional().orElse(null);
        return new ModuleStatus(code, title, variable, true, lastSent, lastSentLabel, errors, errors == 0 ? null : lastError);
    }

    private ModuleStatus fromJournal(String code, String title, String variable, boolean enabled, AuditAction success,
                                     AuditAction failure, OffsetDateTime since) {
        if (!enabled) {
            return off(code, title, variable);
        }
        Optional<AuditEntry> last = auditJournalRepository.findLatest(success);
        Long errors = failure == null ? null : jdbcClient.sql("SELECT COUNT(*) FROM audit_events WHERE action = :action AND occurred_at >= :since")
                .param("action", failure.name()).param("since", since).query(Long.class).single();
        String lastError = errors == null || errors == 0 ? null : auditJournalRepository.findLatest(failure)
                .map(entry -> entry.details() == null ? entry.actionLabel() : entry.details()).orElse(null);
        return new ModuleStatus(code, title, variable, true, last.map(AuditEntry::occurredAt).orElse(null),
                last.map(entry -> entry.actionLabel() + (entry.details() == null ? "" : ": " + entry.details())).orElse(null),
                errors, lastError);
    }
}

record ModuleStatus(
        String code,
        String title,
        String variable,
        boolean enabled,
        OffsetDateTime lastSuccessAt,
        String lastSuccess,
        Long errors,
        String lastError
) {
}
