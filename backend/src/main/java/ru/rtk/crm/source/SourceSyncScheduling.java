package ru.rtk.crm.source;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@Profile("!demo-bootstrap")
class SourceSyncScheduling {
}
