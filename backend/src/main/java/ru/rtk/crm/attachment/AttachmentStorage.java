package ru.rtk.crm.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class AttachmentStorage {
    private static final int BUFFER_SIZE = 8_192;

    private final AttachmentProperties properties;
    private Path root;

    public AttachmentStorage(AttachmentProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        root = properties.storageRoot().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new AttachmentStorageException("Attachment storage cannot be initialized", exception);
        }
    }

    public void store(UUID storageKey, InputStream input, AttachmentUploadInspection inspection) {
        Path target = pathFor(storageKey);
        long sizeBytes = 0;
        MessageDigest digest = sha256();
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                sizeBytes += read;
                if (sizeBytes > properties.maxFileSize().toBytes()) {
                    throw new AttachmentTooLargeException();
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        } catch (IOException exception) {
            deleteQuietly(target);
            throw new AttachmentStorageException("Attachment storage cannot be written", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(target);
            throw exception;
        }
        if (sizeBytes != inspection.sizeBytes()
                || !MessageDigest.isEqual(
                HexFormat.of().parseHex(inspection.checksum()),
                digest.digest()
        )) {
            deleteQuietly(target);
            throw new AttachmentStorageException("Attachment content changed during upload", null);
        }
    }

    public InputStream open(UUID storageKey) {
        try {
            return Files.newInputStream(pathFor(storageKey), StandardOpenOption.READ);
        } catch (IOException exception) {
            throw new AttachmentStorageException("Attachment storage cannot be read", exception);
        }
    }

    public void delete(UUID storageKey) {
        Path target = pathFor(storageKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new AttachmentStorageException("Attachment storage cannot be cleaned", exception);
        }
    }

    public boolean exists(UUID storageKey) {
        return Files.isRegularFile(pathFor(storageKey));
    }

    private Path pathFor(UUID storageKey) {
        Path target = root.resolve(storageKey.toString()).normalize();
        if (!target.getParent().equals(root)) {
            throw new IllegalArgumentException("Attachment storage key is invalid");
        }
        return target;
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
        }
    }
}
