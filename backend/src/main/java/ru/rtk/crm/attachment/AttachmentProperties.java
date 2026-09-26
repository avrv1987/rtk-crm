package ru.rtk.crm.attachment;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("app.attachments")
public record AttachmentProperties(
        Path storageRoot,
        String scannerHost,
        int scannerPort,
        Duration scannerConnectTimeout,
        Duration scannerReadTimeout,
        DataSize maxFileSize
) {
}
