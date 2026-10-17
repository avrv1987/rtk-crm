# Сторонние компоненты и лицензии

Перечень для NFR-08 (воспроизводимость, лицензии зависимостей). Составлен по `backend/pom.xml`, `frontend/package-lock.json`, `compose.yaml` и Dockerfile. Лицензии указаны так, как их объявляют сами компоненты; юридическая оценка совместимости не проводилась.

## Контейнерные образы

| Образ | Назначение | Лицензия основного ПО |
|---|---|---|
| `postgres:16.11-bookworm` | БД CRM и Keycloak | PostgreSQL License |
| `quay.io/keycloak/keycloak:26.7.4` | вход пользователей | Apache-2.0 |
| `clamav/clamav:1.4.6-debian13-slim` | проверка вложений | GPL-2.0 |
| `nginx:1.29.2-alpine` | контейнер `web` | BSD-2-Clause |
| `eclipse-temurin:21.0.8_9-jre` | среда выполнения backend | GPL-2.0 with Classpath Exception |
| `maven:3.9.16-eclipse-temurin-21` | только сборка backend | Apache-2.0 (Maven) |
| `node:24.21.0-alpine` | только сборка frontend | MIT (Node.js) |

ClamAV работает отдельным контейнером; backend обращается к нему по сети через протокол clamd и не включает его код. Базовые образы содержат также пакеты дистрибутивов (Debian, Ubuntu, Alpine, Red Hat UBI) под их собственными лицензиями; эти пакеты в перечень не входят.

## Backend

97 библиотек из областей `compile` и `runtime`, которые попадают в `rtk-crm-backend-*.jar`. Тестовые зависимости (`spring-boot-starter-test`, `spring-security-test`, `h2`) в jar не входят и не перечислены. Перечень получен командой в каталоге `backend/`:

```bash
mvn org.codehaus.mojo:license-maven-plugin:2.4.0:add-third-party -Dlicense.excludedScopes=test,provided,system
```

Результат — `backend/target/generated-sources/license/THIRD-PARTY.txt`. Плагин вызывается с явной версией и в `pom.xml` не подключён. Для Logback действует двойная лицензия: EPL-2.0 или LGPL-2.1.

| Артефакт | Версия | Лицензия |
|---|---|---|
| `ch.qos.logback:logback-classic` | 1.5.34 | EPL-2.0, LGPL-2.1-only |
| `ch.qos.logback:logback-core` | 1.5.34 | EPL-2.0, LGPL-2.1-only |
| `com.fasterxml:classmate` | 1.7.3 | Apache License, Version 2.0 |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.21 | The Apache Software License, Version 2.0 |
| `com.fasterxml.jackson.core:jackson-core` | 2.21.4 | The Apache Software License, Version 2.0 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.4 | The Apache Software License, Version 2.0 |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-toml` | 2.21.4 | The Apache Software License, Version 2.0 |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | 2.21.4 | The Apache Software License, Version 2.0 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.21.4 | The Apache Software License, Version 2.0 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.21.4 | The Apache Software License, Version 2.0 |
| `com.fasterxml.jackson.module:jackson-module-parameter-names` | 2.21.4 | The Apache Software License, Version 2.0 |
| `com.github.stephenc.jcip:jcip-annotations` | 1.0-1 | Apache License, Version 2.0 |
| `com.github.virtuald:curvesapi` | 1.08 | BSD License |
| `com.nimbusds:content-type` | 2.2 | The Apache Software License, Version 2.0 |
| `com.nimbusds:lang-tag` | 1.7 | The Apache Software License, Version 2.0 |
| `com.nimbusds:nimbus-jose-jwt` | 9.37.4 | The Apache Software License, Version 2.0 |
| `com.nimbusds:oauth2-oidc-sdk` | 9.43.6 | Apache License, version 2.0 |
| `com.zaxxer:HikariCP` | 6.3.3 | The Apache Software License, Version 2.0 |
| `com.zaxxer:SparseBitSet` | 1.3 | The Apache Software License, Version 2.0 |
| `commons-codec:commons-codec` | 1.18.0 | Apache-2.0 |
| `commons-io:commons-io` | 2.21.0 | Apache-2.0 |
| `io.micrometer:micrometer-commons` | 1.15.12 | The Apache Software License, Version 2.0 |
| `io.micrometer:micrometer-core` | 1.15.12 | The Apache Software License, Version 2.0 |
| `io.micrometer:micrometer-jakarta9` | 1.15.12 | The Apache Software License, Version 2.0 |
| `io.micrometer:micrometer-observation` | 1.15.12 | The Apache Software License, Version 2.0 |
| `io.swagger.core.v3:swagger-annotations-jakarta` | 2.2.47 | Apache License 2.0 |
| `io.swagger.core.v3:swagger-core-jakarta` | 2.2.47 | Apache License 2.0 |
| `io.swagger.core.v3:swagger-models-jakarta` | 2.2.47 | Apache License 2.0 |
| `jakarta.activation:jakarta.activation-api` | 2.1.4 | EDL 1.0 |
| `jakarta.annotation:jakarta.annotation-api` | 2.1.1 | EPL 2.0, GPL2 w/ CPE |
| `jakarta.validation:jakarta.validation-api` | 3.0.2 | Apache License 2.0 |
| `jakarta.xml.bind:jakarta.xml.bind-api` | 4.0.5 | Eclipse Distribution License - v 1.0 |
| `net.minidev:accessors-smart` | 2.5.2 | The Apache Software License, Version 2.0 |
| `net.minidev:json-smart` | 2.5.2 | The Apache Software License, Version 2.0 |
| `org.apache.commons:commons-collections4` | 4.5.0 | Apache-2.0 |
| `org.apache.commons:commons-compress` | 1.28.0 | Apache-2.0 |
| `org.apache.commons:commons-lang3` | 3.17.0 | Apache-2.0 |
| `org.apache.commons:commons-math3` | 3.6.1 | Apache License, Version 2.0 |
| `org.apache.logging.log4j:log4j-api` | 2.24.3 | Apache-2.0 |
| `org.apache.logging.log4j:log4j-to-slf4j` | 2.24.3 | Apache-2.0 |
| `org.apache.poi:poi` | 5.5.1 | Apache License, Version 2.0 |
| `org.apache.poi:poi-ooxml` | 5.5.1 | Apache License, Version 2.0 |
| `org.apache.poi:poi-ooxml-lite` | 5.5.1 | Apache License, Version 2.0 |
| `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.55 | Apache License, Version 2.0 |
| `org.apache.tomcat.embed:tomcat-embed-el` | 10.1.55 | Apache License, Version 2.0 |
| `org.apache.tomcat.embed:tomcat-embed-websocket` | 10.1.55 | Apache License, Version 2.0 |
| `org.apache.xmlbeans:xmlbeans` | 5.3.0 | The Apache Software License, Version 2.0 |
| `org.flywaydb:flyway-core` | 11.7.2 | Apache License, Version 2.0 |
| `org.flywaydb:flyway-database-postgresql` | 11.7.2 | Apache License, Version 2.0 |
| `org.hdrhistogram:HdrHistogram` | 2.2.2 | BSD-2-Clause, Public Domain, per Creative Commons CC0 |
| `org.hibernate.validator:hibernate-validator` | 8.0.3.Final | Apache License 2.0 |
| `org.jboss.logging:jboss-logging` | 3.6.3.Final | Apache License 2.0 |
| `org.jspecify:jspecify` | 1.0.0 | The Apache License, Version 2.0 |
| `org.latencyutils:LatencyUtils` | 2.0.3 | Public Domain, per Creative Commons CC0 |
| `org.ow2.asm:asm` | 9.7.1 | BSD-3-Clause |
| `org.postgresql:postgresql` | 42.7.11 | BSD-2-Clause |
| `org.slf4j:jul-to-slf4j` | 2.0.18 | MIT |
| `org.slf4j:slf4j-api` | 2.0.18 | MIT |
| `org.springdoc:springdoc-openapi-starter-common` | 2.8.17 | The Apache License, Version 2.0 |
| `org.springdoc:springdoc-openapi-starter-webmvc-api` | 2.8.17 | The Apache License, Version 2.0 |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 2.8.17 | The Apache License, Version 2.0 |
| `org.springframework:spring-aop` | 6.2.19 | Apache License, Version 2.0 |
| `org.springframework:spring-beans` | 6.2.19 | Apache License, Version 2.0 |
| `org.springframework:spring-context` | 6.2.19 | Apache License, Version 2.0 |
| `org.springframework:spring-core` | 6.2.19 | Apache License, Version 2.0 |
| `org.springframework:spring-expression` | 6.2.19 | Apache License, Version 2.0 |
| `org.springframework:spring-jcl` | 6.2.19 | Apache License, Version 2.0 |
| `org.springframework:spring-jdbc` | 6.2.19 | Apache License, Version 2.0 |
| `org.springframework:spring-tx` | 6.2.19 | Apache License, Version 2.0 |
| `org.springframework:spring-web` | 6.2.19 | Apache License, Version 2.0 |
| `org.springframework:spring-webmvc` | 6.2.19 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-actuator` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-actuator-autoconfigure` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-autoconfigure` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-starter` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-starter-actuator` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-starter-jdbc` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-starter-json` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-starter-logging` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-starter-oauth2-client` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-starter-security` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-starter-tomcat` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-starter-validation` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.boot:spring-boot-starter-web` | 3.5.16 | Apache License, Version 2.0 |
| `org.springframework.security:spring-security-config` | 6.5.11 | Apache License, Version 2.0 |
| `org.springframework.security:spring-security-core` | 6.5.11 | Apache License, Version 2.0 |
| `org.springframework.security:spring-security-crypto` | 6.5.11 | Apache License, Version 2.0 |
| `org.springframework.security:spring-security-oauth2-client` | 6.5.11 | Apache License, Version 2.0 |
| `org.springframework.security:spring-security-oauth2-core` | 6.5.11 | Apache License, Version 2.0 |
| `org.springframework.security:spring-security-oauth2-jose` | 6.5.11 | Apache License, Version 2.0 |
| `org.springframework.security:spring-security-web` | 6.5.11 | Apache License, Version 2.0 |
| `org.springframework.session:spring-session-core` | 3.5.7 | Apache License, Version 2.0 |
| `org.springframework.session:spring-session-jdbc` | 3.5.7 | Apache License, Version 2.0 |
| `org.webjars:swagger-ui` | 5.32.2 | Apache-2.0 |
| `org.webjars:webjars-locator-lite` | 1.1.3 | MIT |
| `org.yaml:snakeyaml` | 2.4 | Apache License, Version 2.0 |

## Frontend

Источник — поле `license` пакетов в `frontend/package-lock.json`. В собранный `frontend/dist/` попадают только пакеты без признака `dev`:

| Пакет | Версия | Лицензия |
|---|---|---|
| `react` | 19.3.0 | MIT |
| `react-dom` | 19.3.0 | MIT |
| `scheduler` | 0.28.0 | MIT |

Инструменты сборки и генерации типов (`dev: true`, в `frontend/dist/` не входят; платформенные сборки `@rolldown/binding-*` и `lightningcss-*` устанавливаются только для своей ОС):

| Пакет | Версия | Лицензия |
|---|---|---|
| `@babel/code-frame` | 7.29.7 | MIT |
| `@babel/helper-validator-identifier` | 7.29.7 | MIT |
| `@oxc-project/types` | 0.150.0 | MIT |
| `@redocly/ajv` | 8.11.2 | MIT |
| `@redocly/config` | 0.22.0 | MIT |
| `@redocly/openapi-core` | 1.34.20 | MIT |
| `@rolldown/binding-android-arm-eabi` | 1.2.9 | MIT |
| `@rolldown/binding-android-arm64` | 1.2.9 | MIT |
| `@rolldown/binding-darwin-arm64` | 1.2.9 | MIT |
| `@rolldown/binding-darwin-x64` | 1.2.9 | MIT |
| `@rolldown/binding-freebsd-x64` | 1.2.9 | MIT |
| `@rolldown/binding-linux-arm-gnueabihf` | 1.2.9 | MIT |
| `@rolldown/binding-linux-arm64-gnu` | 1.2.9 | MIT |
| `@rolldown/binding-linux-arm64-musl` | 1.2.9 | MIT |
| `@rolldown/binding-linux-ppc64-gnu` | 1.2.9 | MIT |
| `@rolldown/binding-linux-s390x-gnu` | 1.2.9 | MIT |
| `@rolldown/binding-linux-x64-gnu` | 1.2.9 | MIT |
| `@rolldown/binding-linux-x64-musl` | 1.2.9 | MIT |
| `@rolldown/binding-openharmony-arm64` | 1.2.9 | MIT |
| `@rolldown/binding-win32-arm64-msvc` | 1.2.9 | MIT |
| `@rolldown/binding-win32-x64-msvc` | 1.2.9 | MIT |
| `@rolldown/pluginutils` | 1.0.1 | MIT |
| `@types/react` | 19.3.0 | MIT |
| `@types/react-dom` | 19.3.0 | MIT |
| `@vitejs/plugin-react` | 6.1.1 | MIT |
| `agent-base` | 7.1.4 | MIT |
| `ansi-colors` | 4.1.3 | MIT |
| `argparse` | 2.0.1 | Python-2.0 |
| `balanced-match` | 1.0.2 | MIT |
| `brace-expansion` | 2.1.7 | MIT |
| `change-case` | 5.4.4 | MIT |
| `colorette` | 1.4.0 | MIT |
| `csstype` | 3.2.3 | MIT |
| `debug` | 4.4.3 | MIT |
| `detect-libc` | 2.1.2 | Apache-2.0 |
| `fast-deep-equal` | 3.1.3 | MIT |
| `fdir` | 6.5.0 | MIT |
| `fsevents` | 2.3.3 | MIT |
| `https-proxy-agent` | 7.0.6 | MIT |
| `index-to-position` | 1.2.0 | MIT |
| `js-levenshtein` | 1.1.6 | MIT |
| `js-tokens` | 4.0.0 | MIT |
| `js-yaml` | 4.3.2 | MIT |
| `json-schema-traverse` | 1.0.0 | MIT |
| `lightningcss` | 1.33.0 | MPL-2.0 |
| `lightningcss-android-arm64` | 1.33.0 | MPL-2.0 |
| `lightningcss-darwin-arm64` | 1.33.0 | MPL-2.0 |
| `lightningcss-darwin-x64` | 1.33.0 | MPL-2.0 |
| `lightningcss-freebsd-x64` | 1.33.0 | MPL-2.0 |
| `lightningcss-linux-arm-gnueabihf` | 1.33.0 | MPL-2.0 |
| `lightningcss-linux-arm64-gnu` | 1.33.0 | MPL-2.0 |
| `lightningcss-linux-arm64-musl` | 1.33.0 | MPL-2.0 |
| `lightningcss-linux-x64-gnu` | 1.33.0 | MPL-2.0 |
| `lightningcss-linux-x64-musl` | 1.33.0 | MPL-2.0 |
| `lightningcss-win32-arm64-msvc` | 1.33.0 | MPL-2.0 |
| `lightningcss-win32-x64-msvc` | 1.33.0 | MPL-2.0 |
| `minimatch` | 5.1.9 | ISC |
| `ms` | 2.1.3 | MIT |
| `nanoid` | 3.3.19 | MIT |
| `openapi-typescript` | 7.13.0 | MIT |
| `parse-json` | 8.3.0 | MIT |
| `picocolors` | 1.1.1 | ISC |
| `picomatch` | 4.0.7 | MIT |
| `pluralize` | 8.0.0 | MIT |
| `postcss` | 8.5.28 | MIT |
| `require-from-string` | 2.0.2 | MIT |
| `rolldown` | 1.2.9 | MIT |
| `source-map-js` | 1.2.1 | BSD-3-Clause |
| `supports-color` | 10.2.2 | MIT |
| `tinyglobby` | 0.2.17 | MIT |
| `type-fest` | 4.41.0 | (MIT OR CC0-1.0) |
| `typescript` | 5.9.3 | Apache-2.0 |
| `uri-js-replace` | 1.0.1 | MIT |
| `vite` | 8.3.0 | MIT |
| `yaml-ast-parser` | 0.0.43 | Apache-2.0 |
| `yargs-parser` | 21.1.1 | ISC |

## Шрифты

Шрифт интерфейса входит в собранный `frontend/dist/` и в контейнер `web`. Файлы взяты из Google Fonts и лежат в репозитории, при работе запросов к Google нет.

| Шрифт | Версия | Состав | Лицензия | Где лежит |
|---|---|---|---|---|
| Golos Text | Google Fonts v7 | вариативные woff2, ось `wght` 400–700; подмножества cyrillic, cyrillic-ext, latin | SIL OFL 1.1 | `frontend/public/fonts/golos-text/`, текст лицензии — `OFL.txt` там же |
