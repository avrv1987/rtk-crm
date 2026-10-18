package ru.rtk.crm.max;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

// HTTP-тесты модуля подменяют MaxClient моком, поэтому сборку клиента самим Spring проверяет отдельный тест:
// в 2.4 backend не стартовал при MAX_ENABLED=true, Spring не мог выбрать из двух конструкторов.
class MaxClientWiringTest {
    @Test
    void springBuildsClientFromProperties() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("max", Map.of("app.modules.max.enabled", "true")));
            context.registerBean(MaxProperties.class, () -> new MaxProperties(null, null, Path.of("..", "infra", "max"),
                    null, null, null, null, null, null, null, 0, 0, false, null, null));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(MaxClient.class);
            context.refresh();

            assertThat(context.getBean(MaxClient.class)).isNotNull();
        }
    }
}
