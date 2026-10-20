package ru.rtk.crm.web;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Разбор JSON из запросов к API. Общий ObjectMapper приложения не меняется: им же читаются ответы Moodle, сайта и
// CRM заказчика, а у них свои ограничения размера. Здесь — только то, что присылает клиент:
// - пределы StreamReadConstraints: длинная строка, число из тысяч цифр или глубокая вложенность иначе съедают
//   память и процессор ещё до проверки полей;
// - null внутри списка отвергается сразу: сервисы копируют списки через List.copyOf, который на null падает;
// - год даты вне 1–9999: дальше даты сдвигаются (plusDays, atStartOfDay) и уходят в PostgreSQL, где у date и
//   timestamp свои границы, а сохранённая «вечная» дата потом ломала бы каждый отчёт с этой записью.
@Configuration
public class ApiJsonConfiguration implements WebMvcConfigurer {
    // Тексты в CRM — до 10 000 символов, импорт идёт файлами, а не JSON, поэтому миллиона символов в строке и 10 МБ
    // на тело хватает с большим запасом; вложенность настоящих запросов — не больше 4–5 уровней.
    private static final StreamReadConstraints CONSTRAINTS = StreamReadConstraints.builder()
            .maxDocumentLength(10_000_000)
            .maxStringLength(1_000_000)
            .maxNumberLength(100)
            .maxNestingDepth(50)
            .build();

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
                jackson.setObjectMapper(requestMapper(jackson.getObjectMapper()));
            }
        }
    }

    ObjectMapper requestMapper(ObjectMapper shared) {
        ObjectMapper mapper = shared.copy();
        mapper.getFactory().setStreamReadConstraints(CONSTRAINTS);
        JsonSetter.Value noNullItems = JsonSetter.Value.forContentNulls(Nulls.FAIL);
        for (Class<?> type : List.of(List.class, Set.class, Collection.class)) {
            mapper.configOverride(type).setSetterInfo(noNullItems);
        }
        mapper.registerModule(new SimpleModule("api-text").addDeserializer(String.class, new ApiText()));
        mapper.registerModule(new SimpleModule("api-date-bounds").setDeserializerModifier(new BeanDeserializerModifier() {
            @Override
            public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription description,
                                                          JsonDeserializer<?> deserializer) {
                return Temporal.class.isAssignableFrom(description.getBeanClass()) ? new YearBounded(deserializer) : deserializer;
            }
        }));
        return mapper;
    }

    static boolean yearInRange(Object value) {
        int year;
        try {
            year = switch (value) {
                case LocalDate date -> date.getYear();
                case LocalDateTime dateTime -> dateTime.getYear();
                case OffsetDateTime dateTime -> dateTime.getYear();
                case Instant instant -> instant.atOffset(ZoneOffset.UTC).getYear();
                case null, default -> 1;
            };
        } catch (DateTimeException exception) {
            return false;
        }
        return year >= 1 && year <= 9999;
    }

    static final String NUL_MESSAGE = "Недопустимый символ (NUL); удалите его из текста";

    // Строка только из невидимых символов (пробелы, NBSP, нулевой ширины) — это пусто: дальше её отвергнет обычная
    // проверка «Заполните поле» любого сервиса. Символ NUL база не хранит — отказ сразу, с ошибкой у поля.
    static final class ApiText extends StdScalarDeserializer<String> {
        ApiText() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String value = StringDeserializer.instance.deserialize(parser, context);
            if (value == null) {
                return null;
            }
            if (value.indexOf('\u0000') >= 0) {
                throw InvalidFormatException.from(parser, NUL_MESSAGE, value, String.class);
            }
            return VisibleText.blank(value) ? "" : value;
        }
    }

    private static final class YearBounded extends DelegatingDeserializer {
        private YearBounded(JsonDeserializer<?> delegate) {
            super(delegate);
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) {
            return new YearBounded(delegate);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            Object value = super.deserialize(parser, context);
            if (!yearInRange(value)) {
                throw InvalidFormatException.from(parser, "Год даты должен быть от 1 до 9999", parser.getText(), handledType());
            }
            return value;
        }
    }
}
