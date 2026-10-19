package ru.rtk.crm.work;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@TestConfiguration(proxyBeanMethods = false)
@Import({LmsSignalRepository.class, LmsWorkLearningService.class})
public class LmsLearningTestBeans {
    public static final LmsSignalProperties PROPERTIES = new LmsSignalProperties(14, 50, 7, 7, 40, 50, 25, 14, null, null);

    @Bean
    LmsSignalProperties lmsSignalProperties() {
        return PROPERTIES;
    }
}
