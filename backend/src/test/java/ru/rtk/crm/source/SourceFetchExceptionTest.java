package ru.rtk.crm.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;

import org.junit.jupiter.api.Test;

class SourceFetchExceptionTest {
    @Test
    void networkFailureIsReportedInRussianWithoutJavaClassNames() {
        assertThat(SourceFetchException.unreachable("Moodle", new HttpConnectTimeoutException("connect timed out")))
                .hasMessage("Moodle недоступен: истекло время ожидания ответа")
                .extracting(SourceFetchException::code).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(SourceFetchException.unreachable("Сайт", new ConnectException("Connection refused")))
                .hasMessage("Сайт недоступен: соединение не установлено");
        assertThat(SourceFetchException.unreachable("Сайт", new IOException("EOF reached while reading")))
                .hasMessage("Сайт недоступен: ошибка сети");
    }
}
