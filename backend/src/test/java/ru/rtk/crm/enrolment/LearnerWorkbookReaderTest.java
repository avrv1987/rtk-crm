package ru.rtk.crm.enrolment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import ru.rtk.crm.interaction.InteractionValidationException;

class LearnerWorkbookReaderTest {
    private final LearnerWorkbookReader reader = new LearnerWorkbookReader();

    @Test
    void rejectsArchivesWithTooMuchDecompressedData() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024 * 1024];
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            for (int index = 0; index < 8; index++) {
                zip.putNextEntry(new ZipEntry("part" + index));
                for (int written = 0; written < 10; written++) {
                    zip.write(chunk);
                }
                zip.closeEntry();
            }
        }
        MockMultipartFile file = new MockMultipartFile("file", "learners.xlsx", "application/octet-stream", bytes.toByteArray());

        assertThatThrownBy(() -> reader.read(file))
                .isInstanceOf(InteractionValidationException.class)
                .hasMessage("Суммарный распакованный объём книги больше 64 МиБ");
    }
}
