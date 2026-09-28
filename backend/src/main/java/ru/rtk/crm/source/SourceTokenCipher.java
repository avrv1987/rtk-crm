package ru.rtk.crm.source;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

@Component
class SourceTokenCipher {
    static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;

    SourceTokenCipher(SourceProperties properties) {
        key = key(properties.settingsKey());
    }

    boolean available() {
        return key != null;
    }

    String encrypt(String plaintext, String context) {
        if (key == null) {
            throw new IllegalStateException("Ключ шифрования токенов источников не задан (APP_SOURCES_SETTINGS_KEY)");
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return encoder.encodeToString(iv) + ":" + encoder.encodeToString(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Не удалось зашифровать токен источника", exception);
        }
    }

    Optional<String> decrypt(String stored, String context) {
        String[] parts = stored.split(":", -1);
        if (key == null || parts.length != 2) {
            return Optional.empty();
        }
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] iv = decoder.decode(parts[0]);
            if (iv.length != IV_BYTES) {
                return Optional.empty();
            }
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return Optional.of(new String(cipher.doFinal(decoder.decode(parts[1])), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | AEADBadTagException exception) {
            return Optional.empty();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Не удалось расшифровать токен источника", exception);
        }
    }

    private static SecretKey key(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value.strip().replace('-', '+').replace('_', '/'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Ключ шифрования токенов источников APP_SOURCES_SETTINGS_KEY должен быть записан в Base64");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "Ключ шифрования токенов источников APP_SOURCES_SETTINGS_KEY должен содержать " + KEY_BYTES + " байта (256 бит) в Base64"
            );
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
