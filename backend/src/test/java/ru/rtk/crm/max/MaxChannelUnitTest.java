package ru.rtk.crm.max;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

class MaxChannelUnitTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void redeliveryKeepsTheKeyAndDifferentEventsGetDifferentKeys() throws Exception {
        String started = "{\"update_type\":\"bot_started\",\"timestamp\":100,\"chat_id\":5,\"user\":{\"user_id\":7}}";
        assertThat(MaxUpdateKeys.key(objectMapper.readTree(started))).isEqualTo(MaxUpdateKeys.key(objectMapper.readTree(started)))
                .isEqualTo("bot_started:7:100");
        assertThat(MaxUpdateKeys.key(objectMapper.readTree(started.replace("100", "101")))).isEqualTo("bot_started:7:101");
        assertThat(MaxUpdateKeys.key(objectMapper.readTree(
                "{\"update_type\":\"message_created\",\"timestamp\":1,\"message\":{\"body\":{\"mid\":\"mid.42\"}}}")))
                .isEqualTo("message_created:mid.42");
    }

    @Test
    void callbackKeyIncludesPressTime() throws Exception {
        String first = "{\"update_type\":\"message_callback\",\"timestamp\":5,\"callback\":{\"callback_id\":\"k\",\"timestamp\":5}}";
        String second = first.replace("5", "6");
        assertThat(MaxUpdateKeys.key(objectMapper.readTree(first))).isNotEqualTo(MaxUpdateKeys.key(objectMapper.readTree(second)));
    }

    @Test
    void eventWithoutTypeIsRejectedAndLongKeyIsHashed() throws Exception {
        assertThatThrownBy(() -> MaxUpdateKeys.key(objectMapper.readTree("{\"timestamp\":1}"))).isInstanceOf(IllegalArgumentException.class);
        String mid = "m".repeat(400);
        String key = MaxUpdateKeys.key(objectMapper.readTree(
                "{\"update_type\":\"message_created\",\"message\":{\"body\":{\"mid\":\"" + mid + "\"}}}"));
        assertThat(key).hasSizeLessThanOrEqualTo(MaxUpdateKeys.LIMIT).startsWith("message_created:");
    }

    @Test
    void extraTrustedCertificatesAreReadFromDirectoryWithoutTouchingJvmDefaults(@TempDir Path directory) throws Exception {
        assertThat(MaxTrust.certificates(directory.resolve("missing"))).isEmpty();
        X509Certificate root = anyJdkRoot();
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(root.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        Files.writeString(directory.resolve("russian_trusted_root_ca.pem"), pem);
        Files.writeString(directory.resolve("notes.txt"), "не сертификат");
        assertThat(MaxTrust.certificates(directory)).containsExactly(root);
        assertThat(MaxTrust.sslContext(directory).getProtocol()).isEqualTo("TLS");
    }

    @Test
    void messageBodyFollowsBotApiSchema() {
        MaxMessage message = new MaxMessage("Текст", List.of(
                MaxMessage.Button.openApp("Открыть", "rtk_bot", "work"),
                MaxMessage.Button.link("Открыть CRM", "https://crm.test/#/work")));
        Map<String, Object> body = message.body();
        assertThat(body).containsEntry("text", "Текст").doesNotContainKey("notify");
        assertThat(body.toString()).contains("inline_keyboard", "open_app", "web_app=rtk_bot", "payload=work", "type=link");
        assertThat(MaxMessage.text("x".repeat(5000)).text()).hasSize(MaxMessage.TEXT_LIMIT);
    }

    @Test
    void displayNameFromMaxUser() {
        assertThat(MaxLinkService.name("Анна", "Смирнова", "anna")).isEqualTo("Анна Смирнова (@anna)");
        assertThat(MaxLinkService.name(null, null, "anna")).isEqualTo("@anna");
        assertThat(MaxLinkService.name(" ", null, null)).isEqualTo("без имени");
    }

    @Test
    void randomCodesUseTheAllowedAlphabet() {
        assertThat(MaxCodes.randomCode(24)).matches("[A-Za-z0-9]{24}");
        assertThat(MaxCodes.sessionToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(MaxCodes.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(MaxCodes.constantTimeEquals("abc", null)).isFalse();
    }

    @Test
    void webhookDoesNotConfirmEventWhenDatabaseIsUnavailable() {
        MaxSettingsService settings = mock(MaxSettingsService.class);
        MaxQueueRepository queue = mock(MaxQueueRepository.class);
        when(settings.webhookSecret()).thenReturn(Optional.of("secret"));
        when(queue.insertInbox(any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("down"));
        MaxWebhookController controller = new MaxWebhookController(settings, mock(MaxSettingsRepository.class), queue, objectMapper);
        byte[] body = "{\"update_type\":\"bot_started\",\"timestamp\":1,\"user\":{\"user_id\":7}}".getBytes(StandardCharsets.UTF_8);

        assertThat(controller.receive("secret", body, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(503);
    }

    private static X509Certificate anyJdkRoot() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return ((X509TrustManager) factory.getTrustManagers()[0]).getAcceptedIssuers()[0];
    }
}
