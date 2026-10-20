package ru.rtk.crm.enrolment;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// Служебный запуск scripts/restore-check.sh на восстановленной копии: каждая анкета расшифровывается ключами из
// .env.local. Так проверяется, что ключи анкет сохранены вместе с копией; сами данные в вывод не попадают.
@Component
@Profile("restore-check")
class LearnerRestoreCheck implements ApplicationRunner {
    static final String PREFIX = "RESTORE-CHECK ";
    private static final int BATCH = 500;
    private static final UUID FIRST = new UUID(0, 0);

    private final LearnerRepository repository;
    private final LearnerService learnerService;
    private final LearnerDataCipher cipher;

    LearnerRestoreCheck(LearnerRepository repository, LearnerService learnerService, LearnerDataCipher cipher) {
        this.repository = repository;
        this.learnerService = learnerService;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        Result result = check();
        System.out.println(PREFIX + "learners.checked=" + result.checked());
        System.out.println(PREFIX + "learners.failed=" + result.failed());
        if (result.failed() > 0) {
            throw new IllegalStateException(result.failed() + " learner profiles cannot be decrypted with the configured keys");
        }
    }

    Result check() {
        if (!cipher.enabled()) {
            return new Result(0, 0);
        }
        int checked = 0;
        int failed = 0;
        UUID after = FIRST;
        List<UUID> ids;
        do {
            ids = repository.findEncryptedIdsAfter(after, BATCH);
            for (UUID id : ids) {
                checked++;
                try {
                    learnerService.find(id);
                } catch (RuntimeException exception) {
                    failed++;
                }
                after = id;
            }
        } while (ids.size() == BATCH);
        return new Result(checked, failed);
    }

    record Result(int checked, int failed) {
    }
}
