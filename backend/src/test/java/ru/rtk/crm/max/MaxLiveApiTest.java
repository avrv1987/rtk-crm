package ru.rtk.crm.max;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// Проверка против настоящего Bot API MAX: только чтение (/me, /subscriptions), без рассылок и без подписки.
// Запускается лишь при MAX_LIVE_TOKEN_FILE — пути к файлу с токеном; сам токен в сообщениях проверок не выводится.
// Клиент собран так же, как в приложении: свой SSLContext с корнем Минцифры из infra/max.
// В CI токена нет, поэтому тест там пропускается; без внешней системы ту же логику проверяют MaxChannelHttpTest и MaxClientWiringTest.
@EnabledIfEnvironmentVariable(named = "MAX_LIVE_TOKEN_FILE", matches = ".+")
class MaxLiveApiTest {
    @Test
    void botInfoAndSubscriptionsHaveTheFieldsTheModuleReads() throws IOException {
        String token = Files.readString(Path.of(System.getenv("MAX_LIVE_TOKEN_FILE"))).strip();
        MaxProperties properties = new MaxProperties(null, null, Path.of("..", "infra", "max"), null, null, null, null,
                null, null, null, 0, 0, false, null, null);
        MaxClient client = new MaxClient(properties, new ObjectMapper());

        MaxClient.Response me = client.get(token, "/me");
        assertThat(me.status()).as(me.error()).isEqualTo(200);
        assertThat(me.body().path("is_bot").asBoolean(false)).isTrue();
        assertThat(me.body().path("username").asText("")).isNotBlank();

        MaxClient.Response subscriptions = client.get(token, "/subscriptions");
        assertThat(subscriptions.status()).as(subscriptions.error()).isEqualTo(200);
        assertThat(subscriptions.body().path("subscriptions").isArray()).isTrue();
        subscriptions.body().path("subscriptions").forEach(subscription ->
                assertThat(subscription.path("url").isTextual()).isTrue());
    }
}
