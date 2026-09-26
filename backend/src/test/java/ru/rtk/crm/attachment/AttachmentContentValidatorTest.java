package ru.rtk.crm.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class AttachmentContentValidatorTest {
    private final AttachmentContentValidator validator = new AttachmentContentValidator(new AttachmentProperties(
            Path.of("target", "attachment-test-files"),
            "localhost",
            3310,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            DataSize.ofBytes(16)
    ));

    @Test
    void acceptsEveryRequiredFormatWithAMatchingSignature() {
        Map<String, byte[]> files = Map.of(
                "image.png", new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a},
                "image.jpeg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff},
                "report.pdf", "%PDF-1.4".getBytes(),
                "archive.zip", new byte[]{0x50, 0x4b, 0x03, 0x04},
                "archive.gzip", new byte[]{0x1f, (byte) 0x8b},
                "archive.rar", new byte[]{0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00},
                "letter.doc", new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1},
                "letter.docx", new byte[]{0x50, 0x4b, 0x03, 0x04},
                "table.xls", new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1},
                "table.xlsx", new byte[]{0x50, 0x4b, 0x03, 0x04}
        );

        files.forEach((name, content) -> assertThat(validator.inspect(new MockMultipartFile(
                "file", name, "application/octet-stream", content
        )).originalName()).isEqualTo(name));
    }

    @Test
    void acceptsEquivalentJpegAndGzipFileExtensions() {
        assertThat(validator.inspect(new MockMultipartFile(
                "file", "image.jpg", "image/jpeg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}
        )).mediaType()).isEqualTo("image/jpeg");
        assertThat(validator.inspect(new MockMultipartFile(
                "file", "archive.gz", "application/gzip", new byte[]{0x1f, (byte) 0x8b}
        )).mediaType()).isEqualTo("application/gzip");
    }

    @Test
    void normalizesClientPath() {
        AttachmentUploadInspection inspection = validator.inspect(new MockMultipartFile(
                "file",
                "C:\\temp\\report.PDF",
                "application/pdf",
                "%PDF-1.4".getBytes()
        ));

        assertThat(inspection.originalName()).isEqualTo("report.PDF");
        assertThat(inspection.mediaType()).isEqualTo("application/pdf");
        assertThat(inspection.sizeBytes()).isEqualTo(8);
        assertThat(inspection.checksum()).hasSize(64);
    }

    @Test
    void rejectsUnsupportedExtensionAndMismatchedContent() {
        assertThatThrownBy(() -> validator.inspect(new MockMultipartFile(
                "file", "report.txt", "text/plain", "text".getBytes()
        ))).isInstanceOf(AttachmentValidationException.class);
        assertThatThrownBy(() -> validator.inspect(new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "not a pdf".getBytes()
        ))).isInstanceOf(AttachmentValidationException.class);
    }

    @Test
    void rejectsFilesOverTheConfiguredLimit() {
        assertThatThrownBy(() -> validator.inspect(new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "%PDF-1.4-0123456789".getBytes()
        ))).isInstanceOf(AttachmentTooLargeException.class);
    }
}
