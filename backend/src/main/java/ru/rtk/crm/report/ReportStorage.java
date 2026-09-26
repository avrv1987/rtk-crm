package ru.rtk.crm.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ReportStorage {
    private static final Logger log = LoggerFactory.getLogger(ReportStorage.class);
    private static final String PARTIAL_SUFFIX = ".part";

    private final Path root;

    public ReportStorage(ReportProperties properties) {
        this.root = properties.storageRoot().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new UncheckedIOException("Report storage cannot be initialized", exception);
        }
    }

    public Path partialFile(UUID storageKey) {
        return root.resolve(storageKey + PARTIAL_SUFFIX);
    }

    public Path resultFile(UUID storageKey) {
        return root.resolve(storageKey.toString());
    }

    public void publish(Path partial, UUID storageKey) throws IOException {
        Files.move(partial, resultFile(storageKey), StandardCopyOption.ATOMIC_MOVE);
    }

    public boolean exists(UUID storageKey) {
        return Files.isRegularFile(resultFile(storageKey));
    }

    public void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Report file {} could not be deleted", file.getFileName(), exception);
        }
    }

    public void deletePartialFiles() {
        try (DirectoryStream<Path> partials = Files.newDirectoryStream(root, "*" + PARTIAL_SUFFIX)) {
            for (Path partial : partials) {
                delete(partial);
            }
        } catch (IOException exception) {
            log.warn("Partial report files could not be listed", exception);
        }
    }
}
