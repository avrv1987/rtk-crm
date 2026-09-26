package ru.rtk.crm.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ClamAvAttachmentScannerTest {
    @Test
    void mapsScannerResponsesToSafeStatuses() throws Exception {
        assertThat(scan("stream: OK\0")).isEqualTo(AttachmentScanOutcome.CLEAN);
        assertThat(scan("stream: Win.Test.EICAR_HDB-1 FOUND\0")).isEqualTo(AttachmentScanOutcome.REJECTED);
        assertThat(scan("stream: Heuristics.Encrypted.Archive FOUND\0"))
                .isEqualTo(AttachmentScanOutcome.UNVERIFIABLE);
    }

    @Test
    void treatsAClosedScannerConnectionAsUnverifiable() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket ignored = server.accept()) {
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            AttachmentScanOutcome outcome = scanner(server.getLocalPort()).scan(
                    new ByteArrayInputStream("%PDF-1.4".getBytes(StandardCharsets.US_ASCII)),
                    8
            );
            peer.join();

            assertThat(outcome).isEqualTo(AttachmentScanOutcome.UNVERIFIABLE);
        }
    }

    private AttachmentScanOutcome scan(String response) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = Thread.ofVirtual().start(() -> respond(server, response));
            byte[] document = "%PDF-1.4".getBytes(StandardCharsets.US_ASCII);
            AttachmentScanOutcome outcome = scanner(server.getLocalPort()).scan(
                    new ByteArrayInputStream(document),
                    document.length
            );
            peer.join();
            return outcome;
        }
    }

    private void respond(ServerSocket server, String response) {
        try (Socket socket = server.accept()) {
            socket.getInputStream().readNBytes(10);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            for (int size; (size = input.readInt()) != 0; ) {
                input.readNBytes(size);
            }
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ClamAvAttachmentScanner scanner(int port) {
        return new ClamAvAttachmentScanner(new AttachmentProperties(
                Path.of("target", "attachment-test-files"),
                "127.0.0.1",
                port,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                DataSize.ofMegabytes(20)
        ));
    }
}
