package ru.rtk.crm.enrolment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class LearnerDataCipherTest {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String FIRST_KEY = randomKey(32);
    private static final String SECOND_KEY = randomKey(32);
    private static final String FINGERPRINT_KEY = randomKey(32);
    private static final String SNILS_CONTEXT = "learner:1:SNILS";
    private static final String SERIES_CONTEXT = "learner:1:PASSPORT_SERIES";

    private final LearnerDataCipher cipher = cipher("v1", Map.of("v1", FIRST_KEY), FINGERPRINT_KEY);

    @Test
    void encryptsWithRandomIvAndDecryptsOwnValues() {
        String first = cipher.encrypt(LearnerTestData.VALID_SNILS, SNILS_CONTEXT);
        String second = cipher.encrypt(LearnerTestData.VALID_SNILS, SNILS_CONTEXT);

        assertThat(first).matches("v1:[A-Za-z0-9+/=]{16}:[A-Za-z0-9+/=]+").doesNotContain(LearnerTestData.VALID_SNILS);
        assertThat(first).isNotEqualTo(second);
        assertThat(first.split(":")[1]).isNotEqualTo(second.split(":")[1]);
        assertThat(cipher.decrypt(first, SNILS_CONTEXT)).isEqualTo(LearnerTestData.VALID_SNILS);
        assertThat(cipher.decrypt(second, SNILS_CONTEXT)).isEqualTo(LearnerTestData.VALID_SNILS);
        assertThat(cipher.encrypt(null, SNILS_CONTEXT)).isNull();
        assertThat(cipher.decrypt(null, SNILS_CONTEXT)).isNull();
        assertThatThrownBy(() -> cipher.encrypt("0123", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Не задан контекст зашифрованного значения");
    }

    @Test
    void refusesTamperedValuesAndForeignKeys() {
        String stored = cipher.encrypt("Отделом тестирования № 1", SNILS_CONTEXT);
        String[] parts = stored.split(":");
        byte[] ciphertext = Base64.getDecoder().decode(parts[2]);
        ciphertext[0] ^= 1;
        String tampered = parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(ciphertext);
        String otherIv = parts[0] + ":" + Base64.getEncoder().encodeToString(new byte[LearnerDataCipher.IV_BYTES]) + ":" + parts[2];
        LearnerDataCipher foreign = cipher("v1", Map.of("v1", SECOND_KEY), FINGERPRINT_KEY);

        for (String value : List.of(tampered, otherIv)) {
            assertThatThrownBy(() -> cipher.decrypt(value, SNILS_CONTEXT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Зашифрованное значение повреждено или не соответствует ключу");
        }
        assertThatThrownBy(() -> foreign.decrypt(stored, SNILS_CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Зашифрованное значение повреждено или не соответствует ключу");
        assertThatThrownBy(() -> cipher.decrypt("ул. Тестовая: д. 1: кв. 2", SNILS_CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Зашифрованное значение имеет неверный формат");
    }

    @Test
    void refusesValueMovedToAnotherLearnerOrField() {
        String stored = cipher.encrypt(LearnerTestData.VALID_SNILS, SNILS_CONTEXT);

        for (String context : List.of("learner:2:SNILS", SERIES_CONTEXT)) {
            assertThatThrownBy(() -> cipher.decrypt(stored, context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Зашифрованное значение повреждено или не соответствует ключу");
        }
        assertThat(cipher.decrypt(stored, SNILS_CONTEXT)).isEqualTo(LearnerTestData.VALID_SNILS);
    }

    @Test
    void rotatesKeyVersionKeepingOldValuesReadable() {
        String old = cipher.encrypt("0123", SERIES_CONTEXT);
        LearnerDataCipher rotated = cipher("v2", Map.of("v1", FIRST_KEY, "v2", SECOND_KEY), FINGERPRINT_KEY);
        LearnerDataCipher withoutOldKey = cipher("v2", Map.of("v2", SECOND_KEY), FINGERPRINT_KEY);

        String renewed = rotated.encrypt(rotated.decrypt(old, SERIES_CONTEXT), SERIES_CONTEXT);

        assertThat(rotated.encryptedWithActiveKey(old)).isFalse();
        assertThat(renewed).startsWith("v2:");
        assertThat(rotated.encryptedWithActiveKey(renewed)).isTrue();
        assertThat(rotated.decrypt(renewed, SERIES_CONTEXT)).isEqualTo("0123");
        LearnerDataCipher sameKeyTwice = cipher("v2", Map.of("v1", FIRST_KEY, "v2", FIRST_KEY), FINGERPRINT_KEY);
        String relabeled = "v1" + sameKeyTwice.encrypt("0123", SERIES_CONTEXT).substring(2);
        assertThatThrownBy(() -> sameKeyTwice.decrypt(relabeled, SERIES_CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Зашифрованное значение повреждено или не соответствует ключу");
        assertThatThrownBy(() -> withoutOldKey.decrypt(old, SERIES_CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Нет ключа шифрования версии «v1»");
    }

    @Test
    void fingerprintsAreStableForNormalizedValuesAndSeparatedByKind() {
        LearnerDataCipher sameKeys = cipher("v2", Map.of("v2", SECOND_KEY), FINGERPRINT_KEY);
        LearnerDataCipher otherFingerprintKey = cipher("v1", Map.of("v1", FIRST_KEY), randomKey(32));

        assertThat(cipher.emailFingerprint(" Anna.Testova@Example.test "))
                .matches("[0-9a-f]{64}")
                .isEqualTo(cipher.emailFingerprint("anna.testova@example.test"))
                .isEqualTo(sameKeys.emailFingerprint("anna.testova@example.test"))
                .isNotEqualTo(otherFingerprintKey.emailFingerprint("anna.testova@example.test"));
        assertThat(cipher.phoneFingerprint("8 (900) 000-00-01")).isEqualTo(cipher.phoneFingerprint("+79000000001"));
        assertThat(cipher.snilsFingerprint(LearnerTestData.VALID_SNILS)).isEqualTo(cipher.snilsFingerprint("11223344595"));
        assertThat(cipher.emailFingerprint("+79000000001")).isNotEqualTo(cipher.phoneFingerprint("+79000000001"));
        assertThatThrownBy(() -> cipher.phoneFingerprint("12345"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Телефон не приводится к формату +7XXXXXXXXXX");
    }

    @Test
    void failsAtStartupOnlyWhenEnabledModuleLacksKeys() {
        LearnerDataCipher disabled = new LearnerDataCipher(new EnrolmentProperties(false, "", Map.of("v1", ""), ""));
        assertThatThrownBy(() -> disabled.encrypt("0123", SERIES_CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Модуль «Слушатели» выключен: шифрование недоступно (app.enrolment.enabled=false)");

        assertThatThrownBy(() -> cipher("v1", Map.of("v1", ""), FINGERPRINT_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Модуль «Слушатели» включён, но ключ шифрования активной версии не задан: укажите "
                        + "APP_ENROLMENT_ACTIVE_KEY_VERSION и APP_ENROLMENT_KEYS_V1");
        assertThatThrownBy(() -> cipher("v1", Map.of("v1", randomKey(16)), FINGERPRINT_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Ключ шифрования версии «v1» должен содержать 32 байта (256 бит) в Base64");
        assertThatThrownBy(() -> cipher("v1", Map.of("v1", "не base64"), FINGERPRINT_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Ключ шифрования версии «v1» должен быть записан в Base64");
        assertThatThrownBy(() -> cipher("v1", Map.of("v1", FIRST_KEY), ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Модуль «Слушатели» включён, но не задан ключ отпечатков: укажите APP_ENROLMENT_FINGERPRINT_KEY");
        assertThatThrownBy(() -> cipher("v1", Map.of("V:1", FIRST_KEY), FINGERPRINT_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Версия ключа шифрования должна состоять");
        assertThatThrownBy(() -> cipher("v2", Map.of("v1", FIRST_KEY, "v2", SECOND_KEY), FIRST_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Ключ отпечатков APP_ENROLMENT_FINGERPRINT_KEY совпадает с ключом шифрования "
                        + "APP_ENROLMENT_KEYS_V1; задайте отдельный ключ");
    }

    @Test
    void bindsRotationKeyFromEnvironmentVariableNextToConfiguredKey() {
        StandardEnvironment environment = new StandardEnvironment();
        String systemEnvironment = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        environment.getPropertySources().replace(systemEnvironment, new SystemEnvironmentPropertySource(systemEnvironment, Map.of(
                "APP_ENROLMENT_ACTIVE_KEY_VERSION", "v2",
                "APP_ENROLMENT_KEYS_V2", SECOND_KEY
        )));
        environment.getPropertySources().addLast(new MapPropertySource("application", Map.of(
                "app.enrolment.enabled", "true",
                "app.enrolment.keys.v1", FIRST_KEY,
                "app.enrolment.fingerprint-key", FINGERPRINT_KEY
        )));

        EnrolmentProperties properties = new Binder(ConfigurationPropertySources.get(environment))
                .bind("app.enrolment", EnrolmentProperties.class)
                .get();

        assertThat(properties.activeKeyVersion()).isEqualTo("v2");
        assertThat(properties.keys()).containsOnlyKeys("v1", "v2");
        assertThat(new LearnerDataCipher(properties).encrypt("0123", SERIES_CONTEXT)).startsWith("v2:");
    }

    private static LearnerDataCipher cipher(String active, Map<String, String> keys, String fingerprintKey) {
        return new LearnerDataCipher(new EnrolmentProperties(true, active, keys, fingerprintKey));
    }

    private static String randomKey(int bytes) {
        byte[] key = new byte[bytes];
        RANDOM.nextBytes(key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }
}
