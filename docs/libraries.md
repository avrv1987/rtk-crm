# Библиотеки и компоненты

Перечень по п. 6.2 ТЗ. Файл формируется автоматически из `backend/pom.xml` и `frontend/package-lock.json`, руками не редактируется.

Лицензии Maven берутся из `<licenses>` POM-файлов локального репозитория Maven с переходом к родительскому POM, версии и лицензии npm — из `frontend/package-lock.json`, куда npm записывает поле `license` каждого установленного пакета; runtime-пакеты — записи без признаков `dev` и `devOptional`. Для контейнерных образов указана лицензия основного ПО по данным его проекта; базовый дистрибутив образа содержит пакеты со своими лицензиями.

## Backend: runtime-зависимости Maven

| Библиотека | Версия | Лицензия |
|---|---|---|
| ch.qos.logback:logback-classic | 1.5.34 | EPL-2.0; LGPL-2.1-only |
| ch.qos.logback:logback-core | 1.5.34 | EPL-2.0; LGPL-2.1-only |
| com.fasterxml:classmate | 1.7.3 | Apache License, Version 2.0 |
| com.fasterxml.jackson.core:jackson-annotations | 2.21 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.core:jackson-core | 2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.core:jackson-databind | 2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.dataformat:jackson-dataformat-toml | 2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.dataformat:jackson-dataformat-yaml | 2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.datatype:jackson-datatype-jdk8 | 2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.datatype:jackson-datatype-jsr310 | 2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.module:jackson-module-parameter-names | 2.21.4 | The Apache Software License, Version 2.0 |
| com.github.stephenc.jcip:jcip-annotations | 1.0-1 | Apache License, Version 2.0 |
| com.github.virtuald:curvesapi | 1.08 | BSD License |
| com.nimbusds:content-type | 2.2 | The Apache Software License, Version 2.0 |
| com.nimbusds:lang-tag | 1.7 | The Apache Software License, Version 2.0 |
| com.nimbusds:nimbus-jose-jwt | 9.37.4 | The Apache Software License, Version 2.0 |
| com.nimbusds:oauth2-oidc-sdk | 9.43.6 | Apache License, version 2.0 |
| com.zaxxer:HikariCP | 6.3.3 | The Apache Software License, Version 2.0 |
| com.zaxxer:SparseBitSet | 1.3 | The Apache Software License, Version 2.0 |
| commons-codec:commons-codec | 1.18.0 | Apache-2.0 |
| commons-io:commons-io | 2.21.0 | Apache-2.0 |
| io.micrometer:micrometer-commons | 1.15.12 | The Apache Software License, Version 2.0 |
| io.micrometer:micrometer-core | 1.15.12 | The Apache Software License, Version 2.0 |
| io.micrometer:micrometer-jakarta9 | 1.15.12 | The Apache Software License, Version 2.0 |
| io.micrometer:micrometer-observation | 1.15.12 | The Apache Software License, Version 2.0 |
| io.swagger.core.v3:swagger-annotations-jakarta | 2.2.47 | Apache License 2.0 |
| io.swagger.core.v3:swagger-core-jakarta | 2.2.47 | Apache License 2.0 |
| io.swagger.core.v3:swagger-models-jakarta | 2.2.47 | Apache License 2.0 |
| jakarta.activation:jakarta.activation-api | 2.1.4 | EDL 1.0 |
| jakarta.annotation:jakarta.annotation-api | 2.1.1 | EPL 2.0; GPL2 w/ CPE |
| jakarta.validation:jakarta.validation-api | 3.0.2 | Apache License 2.0 |
| jakarta.xml.bind:jakarta.xml.bind-api | 4.0.5 | Eclipse Distribution License - v 1.0 |
| net.minidev:accessors-smart | 2.5.2 | The Apache Software License, Version 2.0 |
| net.minidev:json-smart | 2.5.2 | The Apache Software License, Version 2.0 |
| org.apache.commons:commons-collections4 | 4.5.0 | Apache-2.0 |
| org.apache.commons:commons-compress | 1.28.0 | Apache-2.0 |
| org.apache.commons:commons-lang3 | 3.17.0 | Apache-2.0 |
| org.apache.commons:commons-math3 | 3.6.1 | Apache License, Version 2.0 |
| org.apache.logging.log4j:log4j-api | 2.24.3 | Apache-2.0 |
| org.apache.logging.log4j:log4j-to-slf4j | 2.24.3 | Apache-2.0 |
| org.apache.poi:poi | 5.5.1 | Apache License, Version 2.0 |
| org.apache.poi:poi-ooxml | 5.5.1 | Apache License, Version 2.0 |
| org.apache.poi:poi-ooxml-lite | 5.5.1 | Apache License, Version 2.0 |
| org.apache.tomcat.embed:tomcat-embed-core | 10.1.55 | Apache License, Version 2.0 |
| org.apache.tomcat.embed:tomcat-embed-el | 10.1.55 | Apache License, Version 2.0 |
| org.apache.tomcat.embed:tomcat-embed-websocket | 10.1.55 | Apache License, Version 2.0 |
| org.apache.xmlbeans:xmlbeans | 5.3.0 | The Apache Software License, Version 2.0 |
| org.flywaydb:flyway-core | 11.7.2 | Apache License, Version 2.0 |
| org.flywaydb:flyway-database-postgresql | 11.7.2 | Apache License, Version 2.0 |
| org.hdrhistogram:HdrHistogram | 2.2.2 | Public Domain, per Creative Commons CC0; BSD-2-Clause |
| org.hibernate.validator:hibernate-validator | 8.0.3.Final | Apache License 2.0 |
| org.jboss.logging:jboss-logging | 3.6.3.Final | Apache License 2.0 |
| org.jspecify:jspecify | 1.0.0 | The Apache License, Version 2.0 |
| org.latencyutils:LatencyUtils | 2.0.3 | Public Domain, per Creative Commons CC0 |
| org.ow2.asm:asm | 9.7.1 | BSD-3-Clause |
| org.postgresql:postgresql | 42.7.11 | BSD-2-Clause |
| org.slf4j:jul-to-slf4j | 2.0.18 | MIT |
| org.slf4j:slf4j-api | 2.0.18 | MIT |
| org.springdoc:springdoc-openapi-starter-common | 2.8.17 | The Apache License, Version 2.0 |
| org.springdoc:springdoc-openapi-starter-webmvc-api | 2.8.17 | The Apache License, Version 2.0 |
| org.springdoc:springdoc-openapi-starter-webmvc-ui | 2.8.17 | The Apache License, Version 2.0 |
| org.springframework:spring-aop | 6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-beans | 6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-context | 6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-core | 6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-expression | 6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-jcl | 6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-jdbc | 6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-tx | 6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-web | 6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-webmvc | 6.2.19 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-actuator | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-actuator-autoconfigure | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-autoconfigure | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-actuator | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-jdbc | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-json | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-logging | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-oauth2-client | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-security | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-tomcat | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-validation | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-web | 3.5.16 | Apache License, Version 2.0 |
| org.springframework.security:spring-security-config | 6.5.11 | Apache License, Version 2.0 |
| org.springframework.security:spring-security-core | 6.5.11 | Apache License, Version 2.0 |
| org.springframework.security:spring-security-crypto | 6.5.11 | Apache License, Version 2.0 |
| org.springframework.security:spring-security-oauth2-client | 6.5.11 | Apache License, Version 2.0 |
| org.springframework.security:spring-security-oauth2-core | 6.5.11 | Apache License, Version 2.0 |
| org.springframework.security:spring-security-oauth2-jose | 6.5.11 | Apache License, Version 2.0 |
| org.springframework.security:spring-security-web | 6.5.11 | Apache License, Version 2.0 |
| org.springframework.session:spring-session-core | 3.5.7 | Apache License, Version 2.0 |
| org.springframework.session:spring-session-jdbc | 3.5.7 | Apache License, Version 2.0 |
| org.webjars:swagger-ui | 5.32.2 | Apache-2.0 |
| org.webjars:webjars-locator-lite | 1.1.3 | MIT |
| org.yaml:snakeyaml | 2.4 | Apache License, Version 2.0 |

## Frontend: runtime-зависимости npm

| Пакет | Версия | Лицензия |
|---|---|---|
| react | 19.3.0 | MIT |
| react-dom | 19.3.0 | MIT |
| scheduler | 0.28.0 | MIT |

## Frontend: инструменты сборки (devDependencies)

Не входят в собранное приложение; их транзитивные зависимости закреплены в `frontend/package-lock.json`.

| Пакет | Версия | Лицензия |
|---|---|---|
| @types/react | 19.3.0 | MIT |
| @types/react-dom | 19.3.0 | MIT |
| @vitejs/plugin-react | 6.1.1 | MIT |
| openapi-typescript | 7.13.0 | MIT |
| typescript | 5.9.3 | Apache-2.0 |
| vite | 8.3.0 | MIT |

## Контейнерные образы

| Образ | Где используется | Лицензия основного ПО |
|---|---|---|
| bitnamilegacy/moodle:4.5.4-debian-12-r0 | infra/moodle/compose.yaml | GPL-3.0-or-later |
| clamav/clamav:1.4.6-debian13-slim | compose.yaml | GPL-2.0-only |
| eclipse-temurin:21.0.8_9-jre | backend/Dockerfile | GPL-2.0-only WITH Classpath-exception-2.0 |
| mariadb:11.4.5 | infra/moodle/compose.yaml | GPL-2.0-only |
| maven:3.9.16-eclipse-temurin-21 | backend/Dockerfile | Apache-2.0 |
| nginx:1.29.2-alpine | frontend/Dockerfile | BSD-2-Clause |
| node:24.21.0-alpine | frontend/Dockerfile | MIT |
| postgres:16.11-bookworm | compose.yaml | PostgreSQL License |
| quay.io/keycloak/keycloak:26.7.4 | compose.yaml | Apache-2.0 |
