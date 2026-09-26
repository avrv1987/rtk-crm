package ru.rtk.crm.attachment;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class AttachmentContentValidator {
    private static final int BUFFER_SIZE = 8_192;
    private static final int PREFIX_SIZE = 32;

    private final AttachmentProperties properties;

    public AttachmentContentValidator(AttachmentProperties properties) {
        this.properties = properties;
    }

    public AttachmentUploadInspection inspect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AttachmentValidationException("file", "Выберите файл");
        }
        if (file.getSize() > properties.maxFileSize().toBytes()) {
            throw new AttachmentTooLargeException();
        }
        String originalName = normalizedName(file.getOriginalFilename());
        AttachmentFormat format = AttachmentFormat.from(originalName);
        MessageDigest digest = sha256();
        long sizeBytes = 0;
        byte[] prefix = new byte[0];
        try (InputStream input = new BufferedInputStream(file.getInputStream())) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (prefix.length < PREFIX_SIZE) {
                    int copied = Math.min(PREFIX_SIZE - prefix.length, read);
                    byte[] extended = Arrays.copyOf(prefix, prefix.length + copied);
                    System.arraycopy(buffer, 0, extended, prefix.length, copied);
                    prefix = extended;
                }
                sizeBytes += read;
                if (sizeBytes > properties.maxFileSize().toBytes()) {
                    throw new AttachmentTooLargeException();
                }
                digest.update(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw new AttachmentValidationException("file", "Файл не удалось прочитать");
        }
        if (sizeBytes == 0) {
            throw new AttachmentValidationException("file", "Выберите файл");
        }
        if (!format.matches(prefix)) {
            throw new AttachmentValidationException("file", "Содержимое файла не соответствует расширению");
        }
        return new AttachmentUploadInspection(
                originalName,
                format.mediaType(),
                sizeBytes,
                HexFormat.of().formatHex(digest.digest())
        );
    }

    private String normalizedName(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            throw new AttachmentValidationException("file", "У файла нет имени");
        }
        String normalizedPath = sourceName.replace('\\', '/');
        String name = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1).trim();
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 255
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new AttachmentValidationException("file", "Недопустимое имя файла");
        }
        return name;
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private enum AttachmentFormat {
        PNG("png", "image/png"),
        JPEG("jpeg", "image/jpeg"),
        PDF("pdf", "application/pdf"),
        ZIP("zip", "application/zip"),
        GZIP("gzip", "application/gzip"),
        RAR("rar", "application/vnd.rar"),
        DOC("doc", "application/msword"),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        XLS("xls", "application/vnd.ms-excel"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        private final String extension;
        private final String mediaType;

        AttachmentFormat(String extension, String mediaType) {
            this.extension = extension;
            this.mediaType = mediaType;
        }

        static AttachmentFormat from(String fileName) {
            int separator = fileName.lastIndexOf('.');
            if (separator < 1 || separator == fileName.length() - 1) {
                throw new AttachmentValidationException("file", "Такой тип файла не поддерживается");
            }
            String extension = fileName.substring(separator + 1).toLowerCase(Locale.ROOT);
            if (extension.equals("jpg")) {
                return JPEG;
            }
            if (extension.equals("gz")) {
                return GZIP;
            }
            for (AttachmentFormat format : values()) {
                if (format.extension.equals(extension)) {
                    return format;
                }
            }
            throw new AttachmentValidationException("file", "Такой тип файла не поддерживается");
        }

        String mediaType() {
            return mediaType;
        }

        boolean matches(byte[] prefix) {
            return switch (this) {
                case PNG -> startsWith(prefix, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
                case JPEG -> startsWith(prefix, 0xff, 0xd8, 0xff);
                case PDF -> startsWith(prefix, 0x25, 0x50, 0x44, 0x46, 0x2d);
                case ZIP, DOCX, XLSX -> zipSignature(prefix);
                case GZIP -> startsWith(prefix, 0x1f, 0x8b);
                case RAR -> startsWith(prefix, 0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)
                        || startsWith(prefix, 0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00);
                case DOC, XLS -> startsWith(prefix, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1);
            };
        }

        private static boolean zipSignature(byte[] prefix) {
            return startsWith(prefix, 0x50, 0x4b, 0x03, 0x04)
                    || startsWith(prefix, 0x50, 0x4b, 0x05, 0x06)
                    || startsWith(prefix, 0x50, 0x4b, 0x07, 0x08);
        }

        private static boolean startsWith(byte[] value, int... expected) {
            if (value.length < expected.length) {
                return false;
            }
            for (int index = 0; index < expected.length; index++) {
                if ((value[index] & 0xff) != expected[index]) {
                    return false;
                }
            }
            return true;
        }
    }
}
