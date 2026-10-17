package ru.rtk.crm.enrolment;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class LearnerRekeyJob {
    static final int BATCH = 100;

    private static final Logger LOGGER = LoggerFactory.getLogger(LearnerRekeyJob.class);
    private static final UUID FIRST = new UUID(0, 0);

    private final LearnerRepository repository;
    private final LearnerService learnerService;
    private final LearnerDataCipher cipher;

    LearnerRekeyJob(LearnerRepository repository, LearnerService learnerService, LearnerDataCipher cipher) {
        this.repository = repository;
        this.learnerService = learnerService;
        this.cipher = cipher;
    }

    // Ротация ключа без простоя: записи со старым ключом читаются, пока задача не перешифрует их порциями.
    // Старый ключ нельзя удалять из окружения, пока счётчик «со старым ключом» на экране администратора не равен нулю.
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    void reencryptWithActiveKey() {
        if (!cipher.enabled()) {
            return;
        }
        String activeVersion = cipher.activeKeyVersion();
        int reencrypted = 0;
        int failed = 0;
        // Обход по курсору «id > after», а не по смещению: перешифрованная строка выпадает из выборки, а ошибочная
        // не зацикливает задачу, потому что курсор в любом случае проходит дальше.
        UUID after = FIRST;
        List<UUID> ids;
        do {
            ids = repository.findIdsWithOtherKey(activeVersion, after, BATCH);
            for (UUID id : ids) {
                try {
                    if (learnerService.reencrypt(id)) {
                        reencrypted++;
                    }
                } catch (IllegalStateException exception) {
                    // Анкета без ключа или с повреждённым значением не должна останавливать остальные;
                    // причина идёт в журнал без ПДн.
                    failed++;
                    LOGGER.warn("Learner {} was not re-encrypted with key version {}: {}", id, activeVersion, exception.getMessage());
                }
                after = id;
            }
        } while (ids.size() == BATCH);
        if (reencrypted + failed > 0) {
            LOGGER.info("Learner profiles re-encrypted with key version {}: {}, failed: {}", activeVersion, reencrypted, failed);
        }
    }
}
