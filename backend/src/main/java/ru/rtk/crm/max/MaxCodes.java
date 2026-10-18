package ru.rtk.crm.max;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

// Одноразовые коды, токены сессий и секрет вебхука. В базе хранится только SHA-256: утечка таблицы не даёт ни
// привязать чужой MAX, ни войти в мини-приложение.
final class MaxCodes {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private MaxCodes() {
    }

    // 24 символа из 62 — около 143 бит; алфавит подходит и для параметра start ссылки MAX, и для секрета вебхука
    // (MAX допускает в секрете только A-Z, a-z, 0-9, «_» и «-»).
    static String randomCode(int length) {
        char[] code = new char[length];
        for (int index = 0; index < length; index++) {
            code[index] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }

    static String sessionToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
