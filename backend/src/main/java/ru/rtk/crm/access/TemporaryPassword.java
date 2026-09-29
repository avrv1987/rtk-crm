package ru.rtk.crm.access;

import java.security.SecureRandom;

public final class TemporaryPassword {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPassword() {
    }

    public static String generate() {
        while (true) {
            StringBuilder password = new StringBuilder(LENGTH);
            for (int index = 0; index < LENGTH; index++) {
                password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            String value = password.toString();
            if (value.chars().anyMatch(Character::isUpperCase) && value.chars().anyMatch(Character::isLowerCase)
                    && value.chars().anyMatch(Character::isDigit)) {
                return value;
            }
        }
    }
}
