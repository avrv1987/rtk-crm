package ru.rtk.crm.max;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

// Эталон посчитан независимо (Node.js crypto) по алгоритму dev.max.ru/docs/webapps/validation.
class MaxInitDataTest {
    static final String TOKEN = "test-bot-token";
    static final String REFERENCE = "auth_date=1771409719&query_id=q-1"
            + "&user=%7B%22id%22%3A777%2C%22first_name%22%3A%22%D0%90%D0%BD%D0%BD%D0%B0%22%7D&start_param=work"
            + "&hash=68bb50349973a71c76e0b74e9830eac4713f675ca291fe301aec398894ec2a02";
    private static final Instant SIGNED_AT = Instant.ofEpochSecond(1771409719);
    private static final Duration HOUR = Duration.ofHours(1);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void referenceInitDataIsAccepted() {
        MaxInitData.Verified verified = MaxInitData.verify(REFERENCE, TOKEN, HOUR, SIGNED_AT.plusSeconds(60), objectMapper);
        assertThat(verified.userId()).isEqualTo(777);
        assertThat(verified.firstName()).isEqualTo("Анна");
        assertThat(verified.startParam()).isEqualTo("work");
    }

    // Формат запуска из документации MAX: initData лежит во фрагменте значением WebAppData, закодированным ещё раз.
    // Мост MAX и наш фронтенд раскодируют фрагмент один раз и отдают серверу строку того же вида, что REFERENCE.
    @Test
    void initDataFromLaunchFragmentIsAccepted() {
        String fragment = "WebAppData=" + URLEncoder.encode(REFERENCE, StandardCharsets.UTF_8).replace("+", "%20")
                + "&WebAppPlatform=web&WebAppVersion=26.2.8";
        String fromFragment = URLDecoder.decode(fragment.split("&")[0].substring("WebAppData=".length()), StandardCharsets.UTF_8);
        assertThat(fromFragment).isEqualTo(REFERENCE);
        assertThat(MaxInitData.verify(fromFragment, TOKEN, HOUR, SIGNED_AT.plusSeconds(60), objectMapper).userId()).isEqualTo(777);
    }

    @Test
    void plusSignInValueIsKeptAsInReferenceDecoding() {
        String user = "{\"id\":777,\"first_name\":\"A+B\"}";
        String hash = MaxInitData.sign(new TreeMap<>(Map.of("auth_date", "1771409719", "user", user)), TOKEN);
        String initData = "auth_date=1771409719&user=" + URLEncoder.encode(user, StandardCharsets.UTF_8).replace("%2B", "+")
                + "&hash=" + hash;
        assertThat(MaxInitData.verify(initData, TOKEN, HOUR, SIGNED_AT.plusSeconds(60), objectMapper).firstName()).isEqualTo("A+B");
    }

    @Test
    void tamperedFieldOrForeignTokenIsRejected() {
        assertInvalid(REFERENCE.replace("%3A777", "%3A778"), TOKEN);
        assertInvalid(REFERENCE.replace("start_param=work", "start_param=admin"), TOKEN);
        assertInvalid(REFERENCE, "another-token");
    }

    @Test
    void repeatedOrMissingParametersAreRejected() {
        assertInvalid(REFERENCE + "&start_param=work", TOKEN);
        assertInvalid(REFERENCE.substring(0, REFERENCE.indexOf("&hash=")), TOKEN);
        assertInvalid("", TOKEN);
        assertInvalid("x".repeat(MaxInitData.LIMIT + 1), TOKEN);
        assertInvalid("auth_date", TOKEN);
    }

    @Test
    void initDataOlderThanOneHourIsExpiredAndFutureDateIsRejected() {
        assertThatThrownBy(() -> MaxInitData.verify(REFERENCE, TOKEN, HOUR, SIGNED_AT.plus(Duration.ofMinutes(61)), objectMapper))
                .isInstanceOfSatisfying(MaxException.class, exception -> assertThat(exception.code()).isEqualTo("MAX_INIT_DATA_EXPIRED"));
        assertThatThrownBy(() -> MaxInitData.verify(REFERENCE, TOKEN, HOUR, SIGNED_AT.minus(Duration.ofMinutes(10)), objectMapper))
                .isInstanceOfSatisfying(MaxException.class, exception -> assertThat(exception.code()).isEqualTo("MAX_INIT_DATA_INVALID"));
    }

    private void assertInvalid(String initData, String token) {
        assertThatThrownBy(() -> MaxInitData.verify(initData, token, HOUR, SIGNED_AT.plusSeconds(60), objectMapper))
                .isInstanceOfSatisfying(MaxException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("MAX_INIT_DATA_INVALID");
                    assertThat(exception.status().value()).isEqualTo(401);
                });
    }
}
