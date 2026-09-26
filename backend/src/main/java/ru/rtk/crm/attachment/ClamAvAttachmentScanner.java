package ru.rtk.crm.attachment;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

@Component
public class ClamAvAttachmentScanner implements AttachmentScanner {
    private static final int BUFFER_SIZE = 8_192;
    private static final int MAX_RESPONSE_SIZE = 4_096;

    private final AttachmentProperties properties;

    public ClamAvAttachmentScanner(AttachmentProperties properties) {
        this.properties = properties;
    }

    @Override
    public AttachmentScanOutcome scan(InputStream input, long sizeBytes) {
        if (sizeBytes > properties.maxFileSize().toBytes()) {
            return AttachmentScanOutcome.UNVERIFIABLE;
        }
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(properties.scannerHost(), properties.scannerPort()),
                    timeoutMillis(properties.scannerConnectTimeout())
            );
            socket.setSoTimeout(timeoutMillis(properties.scannerReadTimeout()));
            try (DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.writeInt(read);
                    output.write(buffer, 0, read);
                }
                output.writeInt(0);
                output.flush();
                return outcome(readResponse(socket.getInputStream()));
            }
        } catch (IOException exception) {
            return AttachmentScanOutcome.UNVERIFIABLE;
        }
    }

    private String readResponse(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (int next; (next = input.read()) != -1; ) {
            if (next == 0 || next == '\n') {
                return result.toString(StandardCharsets.US_ASCII);
            }
            if (result.size() >= MAX_RESPONSE_SIZE) {
                throw new IOException("ClamAV response exceeds the configured limit");
            }
            result.write(next);
        }
        throw new IOException("ClamAV closed the response without a terminator");
    }

    private AttachmentScanOutcome outcome(String response) {
        if (response.endsWith(": OK")) {
            return AttachmentScanOutcome.CLEAN;
        }
        if (!response.endsWith(" FOUND")) {
            return AttachmentScanOutcome.UNVERIFIABLE;
        }
        String normalized = response.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("heuristics.encrypted") || normalized.contains("heuristics.limits")) {
            return AttachmentScanOutcome.UNVERIFIABLE;
        }
        return AttachmentScanOutcome.REJECTED;
    }

    private int timeoutMillis(java.time.Duration timeout) {
        long millis = timeout.toMillis();
        if (millis <= 0 || millis > Integer.MAX_VALUE) {
            throw new IllegalStateException("Attachment scanner timeout is invalid");
        }
        return (int) millis;
    }
}
