package ru.rtk.crm.catalogimport;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionNextStep;
import ru.rtk.crm.interaction.InteractionValidationException;

// «Шаг по умолчанию при импорте»: карточки, созданные импортом, и массовое действие руководителя получают этот шаг
// и срок. Значение по умолчанию — «Связаться с вузом», 7 дней (V69); меняет администратор.
@Service
public class ImportSettingsService {
    private final MigrationImportRepository repository;
    private final AuditJournalRepository auditJournalRepository;

    ImportSettingsService(MigrationImportRepository repository, AuditJournalRepository auditJournalRepository) {
        this.repository = repository;
        this.auditJournalRepository = auditJournalRepository;
    }

    public ImportSettingsView settings() {
        MigrationImportRepository.ImportSettings settings = current();
        return new ImportSettingsView(settings.defaultStepText(), settings.defaultStepDays());
    }

    public ImportSettingsView view(CrmProfile profile) {
        requireAdmin(profile);
        return settings();
    }

    @Transactional
    public ImportSettingsView update(CrmProfile profile, ImportSettingsView request, String requestId) {
        requireAdmin(profile);
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        String text = request.defaultStepText() == null ? "" : request.defaultStepText().strip();
        if (text.isEmpty() || text.length() > 500) {
            throw new InteractionValidationException("defaultStepText", "Укажите текст шага до 500 символов");
        }
        if (request.defaultStepDays() == null || request.defaultStepDays() < 1 || request.defaultStepDays() > 365) {
            throw new InteractionValidationException("defaultStepDays", "Срок — от 1 до 365 дней");
        }
        MigrationImportRepository.ImportSettings before = current();
        MigrationImportRepository.ImportSettings next = new MigrationImportRepository.ImportSettings(text, request.defaultStepDays());
        if (!before.equals(next)) {
            repository.saveSettings(next, profile.id(), OffsetDateTime.now());
            auditJournalRepository.record(AuditAction.IMPORT_SETTINGS_CHANGED, profile.id(), "IMPORT_SETTINGS", null,
                    "Шаг по умолчанию при импорте",
                    "«" + before.defaultStepText() + "», " + before.defaultStepDays() + " дн. → «" + next.defaultStepText() + "», "
                            + next.defaultStepDays() + " дн.",
                    requestId);
        }
        return new ImportSettingsView(next.defaultStepText(), next.defaultStepDays());
    }

    public InteractionNextStep defaultStep(OffsetDateTime now) {
        MigrationImportRepository.ImportSettings settings = current();
        return new InteractionNextStep(settings.defaultStepText(), now.plusDays(settings.defaultStepDays()));
    }

    private MigrationImportRepository.ImportSettings current() {
        return repository.findSettings().orElse(MigrationImportRepository.ImportSettings.DEFAULT);
    }

    private static void requireAdmin(CrmProfile profile) {
        if (profile == null || profile.role() != UserRole.ADMIN) {
            throw new CatalogImportAccessDeniedException();
        }
    }

    public record ImportSettingsView(String defaultStepText, Integer defaultStepDays) {
    }
}
