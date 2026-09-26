package ru.rtk.crm.enrolment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

@Component
public class LearnerDataCipher {
    static final int KEY_BYTES = 32;
    static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String MAC = "HmacSHA256";
    private static final Pattern VERSION = Pattern.compile("[a-z0-9]{1,16}");

    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKey> keys;
    private final String activeVersion;
    private final SecretKey fingerprintKey;

    public LearnerDataCipher(EnrolmentProperties properties) {
        if (properties.enabled()) {
            keys = encryptionKeys(properties);
            activeVersion = activeVersion(properties, keys);
            fingerprintKey = fingerprintKey(properties, keys);
        } else {
            keys = Map.of();
            activeVersion = null;
            fingerprintKey = null;
        }
    }

    public String encrypt(String plaintext, String context) {
        if (plaintext == null) {
            return null;
        }
        requireEnabled();
        byte[] aad = aad(activeVersion, context);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeVersion), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return activeVersion + ":" + encoder.encodeToString(iv) + ":" + encoder.encodeToString(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Не удалось зашифровать значение", exception);
        }
    }

    public String decrypt(String stored, String context) {
        if (stored == null) {
            return null;
        }
        requireEnabled();
        String[] parts = stored.split(":", -1);
        if (parts.length != 3 || !VERSION.matcher(parts[0]).matches()) {
            throw new IllegalStateException("Зашифрованное значение имеет неверный формат");
        }
        SecretKey key = keys.get(parts[0]);
        if (key == null) {
            throw new IllegalStateException("Нет ключа шифрования версии «" + parts[0] + "»");
        }
        byte[] aad = aad(parts[0], context);
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] iv = decoder.decode(parts[1]);
            if (iv.length != IV_BYTES) {
                throw new IllegalStateException("Зашифрованное значение имеет неверный формат");
            }
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            return new String(cipher.doFinal(decoder.decode(parts[2])), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new IllegalStateException("Зашифрованное значение повреждено или не соответствует ключу", exception);
        }
    }

    public boolean encryptedWithActiveKey(String stored) {
        requireEnabled();
        return stored != null && stored.startsWith(activeVersion + ":");
    }

    public String emailFingerprint(String email) {
        return email == null ? null : fingerprint("email", LearnerRules.emailKey(email));
    }

    public String phoneFingerprint(String phone) {
        if (phone == null) {
            return null;
        }
        String normalized = LearnerRules.normalizePhone(phone);
        if (normalized == null) {
            throw new IllegalArgumentException("Телефон не приводится к формату +7XXXXXXXXXX");
        }
        return fingerprint("phone", normalized);
    }

    public String snilsFingerprint(String snils) {
        if (snils == null) {
            return null;
        }
        String normalized = LearnerRules.normalizeSnils(snils);
        if (normalized == null) {
            throw new IllegalArgumentException("СНИЛС не приводится к 11 цифрам");
        }
        return fingerprint("snils", normalized);
    }

    private String fingerprint(String kind, String value) {
        requireEnabled();
        try {
            Mac mac = Mac.getInstance(MAC);
            mac.init(fingerprintKey);
            return HexFormat.of().formatHex(mac.doFinal((kind + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Не удалось вычислить отпечаток значения", exception);
        }
    }

    private static byte[] aad(String version, String context) {
        if (context == null || context.isBlank()) {
            throw new IllegalArgumentException("Не задан контекст зашифрованного значения");
        }
        return (version + ":" + context).getBytes(StandardCharsets.UTF_8);
    }

    private void requireEnabled() {
        if (activeVersion == null) {
            throw new IllegalStateException("Модуль «Слушатели» выключен: шифрование недоступно (app.enrolment.enabled=false)");
        }
    }

    private static Map<String, SecretKey> encryptionKeys(EnrolmentProperties properties) {
        Map<String, SecretKey> keys = new HashMap<>();
        properties.keys().forEach((version, value) -> {
            if (!VERSION.matcher(version).matches()) {
                throw new IllegalStateException(
                        "Версия ключа шифрования должна состоять из 1–16 строчных латинских букв и цифр, например v1"
                );
            }
            if (value != null && !value.isBlank()) {
                byte[] key = decode(value, "Ключ шифрования версии «" + version + "»");
                if (key.length != KEY_BYTES) {
                    throw new IllegalStateException(
                            "Ключ шифрования версии «" + version + "» должен содержать " + KEY_BYTES + " байта (256 бит) в Base64"
                    );
                }
                keys.put(version, new SecretKeySpec(key, "AES"));
            }
        });
        return Map.copyOf(keys);
    }

    private static String activeVersion(EnrolmentProperties properties, Map<String, SecretKey> keys) {
        String active = properties.activeKeyVersion() == null ? "" : properties.activeKeyVersion().strip();
        if (!keys.containsKey(active)) {
            String variable = active.isEmpty() ? "APP_ENROLMENT_KEYS_<версия>" : "APP_ENROLMENT_KEYS_" + active.toUpperCase(Locale.ROOT);
            throw new IllegalStateException(
                    "Модуль «Слушатели» включён, но ключ шифрования активной версии не задан: укажите "
                            + "APP_ENROLMENT_ACTIVE_KEY_VERSION и " + variable
            );
        }
        return active;
    }

    private static SecretKey fingerprintKey(EnrolmentProperties properties, Map<String, SecretKey> keys) {
        String value = properties.fingerprintKey();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Модуль «Слушатели» включён, но не задан ключ отпечатков: укажите APP_ENROLMENT_FINGERPRINT_KEY"
            );
        }
        byte[] key = decode(value, "Ключ отпечатков");
        if (key.length < KEY_BYTES) {
            throw new IllegalStateException("Ключ отпечатков должен содержать не меньше " + KEY_BYTES + " байт в Base64");
        }
        keys.forEach((version, encryptionKey) -> {
            if (MessageDigest.isEqual(encryptionKey.getEncoded(), key)) {
                throw new IllegalStateException("Ключ отпечатков APP_ENROLMENT_FINGERPRINT_KEY совпадает с ключом шифрования "
                        + "APP_ENROLMENT_KEYS_" + version.toUpperCase(Locale.ROOT) + "; задайте отдельный ключ");
            }
        });
        return new SecretKeySpec(key, MAC);
    }

    private static byte[] decode(String value, String name) {
        try {
            return Base64.getDecoder().decode(value.strip().replace('-', '+').replace('_', '/'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " должен быть записан в Base64");
        }
    }
}
