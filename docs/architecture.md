# Архитектура реализации

## Статус

Файл описывает реализованный контур. Установка на Linux, публикационный вариант с TLS и переменные окружения — в `docs/deploy.md`.

## Контур входа и BFF

Один браузерный origin обслуживает Nginx на `PUBLIC_ORIGIN`. Для локального Compose это `http://rtk.localhost:8081`: браузер разрешает `.localhost` в loopback. Nginx отдаёт React-приложение, проксирует `/api/` в Spring Boot и `/idp/` в Keycloak; при этом снимает public префикс `/idp` перед внутренним Keycloak. `/swagger-ui`, `/v3/api-docs` и `/openapi.yaml` также проксируются в Spring Boot. Прокси заменяет поступившие `X-Forwarded-For` и `X-Forwarded-Proto` адресом непосредственного клиента и собственной схемой; исключение — запросы от доверенного TLS-прокси из `TRUSTED_PROXY_CIDR`. `X-Forwarded-Host` всегда равен `Host`, а клиентские `Forwarded`, `X-Forwarded-Port` и `X-Forwarded-Prefix` удаляются для всех адресов. Внешние сервисы не публикуют порты напрямую.

Spring Boot работает как BFF. OIDC code flow создаёт серверную Spring Session в PostgreSQL; браузер получает только HttpOnly session cookie и CSRF cookie/token. Access token и refresh token не передаются в React и не сохраняются в `localStorage`.

CRM-профиль связывается с OIDC identity по паре `issuer`/`sub`. Каждый запрос бизнес-API использует активный профиль; identity без активного CRM-профиля получает JSON `403`. Первый успешный вход identity без профиля создаёт неактивный профиль «ожидает активации» (`403 CRM_PROFILE_PENDING`), доступ к данным открывает администратор — `docs/roles.md`. Незаутентифицированный `/api/**` получает JSON `401`, а не HTML redirect.

Flyway является единственным владельцем production-схемы. Первая миграция создаёт команды, CRM-профили, организации и таблицы Spring Session. ORM не изменяет схему автоматически.

## Контракт

Канонический OpenAPI 3.1 находится в `backend/src/main/resources/static/openapi.yaml`. Frontend генерирует TypeScript-типы локально командой `npm run generate:api`; исходники не содержат независимой ручной модели API.

Контракт реализует OIDC/login/logout, `/api/me`, `/api/csrf`, JSON errors и correlation `requestId`, а также DTO для заданий отчётов и административных функций.

`/api/organizations` и `/api/organizations/{id}` реализуют чтение каталога организаций. КАМ видит только свои организации той же команды, руководитель — организации своей команды, включая неназначенные; администратор до реализации явной business scope не получает неявное бизнес-чтение. Чужая карточка маскируется ответом `404`; пагинация и сортировка выполняются в SQL. Список принимает поиск по части названия `q` и отбор `requiresAssignment=true`: вуз без ответственного или с ответственным, который больше не активный КАМ команды вуза. В ответе организации есть `ownerManagerName`, `teamName` и `requiresAssignment`, чтобы интерфейс не показывал UUID.

Пользовательские тексты ошибок (`message` и `fieldErrors`) на русском, коды `ApiError.code` не меняются. Сообщения аннотаций Bean Validation заданы в `backend/src/main/resources/ValidationMessages.properties` и не зависят от языка браузера; тексты `ApiExceptionHandler`, `ApiErrorController`, `JsonAccessDeniedHandler`, `JsonAuthenticationEntryPoint` и ответ nginx на 413 (`infra/web/crm-site.conf`) тоже русские. Внутренние исключения (`IllegalStateException` и подобные) остаются в журнале сервера и пользователю не показываются: он получает `INTERNAL_ERROR`.

## Swagger UI, ошибки и выход

Swagger UI открывается анонимно по `/swagger-ui/index.html` и показывает только ручной `openapi.yaml`: springdoc включён ради интерфейса, генерация `/v3/api-docs` выключена (`springdoc.enable-default-api-docs=false`), наружу открыт лишь `/v3/api-docs/swagger-config`. Встроенная проверка спецификации на validator.swagger.io выключена (`springdoc.swagger-ui.validator-url=none`), поэтому страница не обращается к внешнему сервису и не передаёт ему адрес `openapi.yaml`. Кнопка «Try it out» работает в сессии браузера на том же origin; изменяющие методы требуют заголовок `X-CSRF-TOKEN` из `GET /api/csrf`.

Каждый запрос получает `requestId`: он возвращается в заголовке `X-Request-Id`, в теле `ApiError` и записывается в MDC, поэтому попадает в каждую строку журнала сервера (`logging.pattern.correlation`). Ошибки Spring MVC (404, 405, 415, необработанное исключение) проходят через ERROR dispatch к `/error`; правило `dispatcherTypeMatchers(ERROR).permitAll()` сохраняет исходный статус, иначе `anyRequest().denyAll()` превращал бы их в 403. `ApiErrorController` отвечает JSON `{code, message, requestId}` без stack trace, а любой 5xx пишет в журнал со stack trace под тем же `requestId`. Исключение берётся из `jakarta.servlet.error.exception`, а если его нет (Spring MVC сам разрешил исключение и вызвал `sendError`), — из атрибута `DispatcherServlet.EXCEPTION_ATTRIBUTE`. Перечень кодов — перечисление `ApiError.code` в OpenAPI.

`POST /api/auth/logout` завершает серверную сессию, удаляет cookie `SESSION` и `XSRF-TOKEN` и отвечает `200 {logoutUrl}`. Адрес — `end_session_endpoint` Keycloak на публичном issuer (`${issuer}/protocol/openid-connect/logout`) с `client_id`, `id_token_hint` и `post_logout_redirect_uri=${PUBLIC_ORIGIN}/`. SPA переходит по нему, Keycloak завершает SSO-сессию и возвращает браузер в CRM, поэтому следующий «Войти» снова спрашивает пароль. Клиент `crm-bff` должен разрешать этот адрес атрибутом `post.logout.redirect.uris` (`scripts/bootstrap-demo.ps1`). Черновики пользователя на устройстве удаляются при любом исходе запроса выхода.

## Инфраструктура

`compose.yaml` содержит `web`, `backend`, `postgres` и `keycloak` (профиль `core`, без метки), `clamav` (профиль `security`), одноразовый `backend-bootstrap` и `site-fixture` (профиль `demo`). PostgreSQL создаёт раздельные БД и роли CRM/Keycloak. ClamAV не имеет опубликованного порта, имеет память, PID-лимит и healthcheck. Backend имеет healthcheck `/actuator/health` и стартует после healthy PostgreSQL, Keycloak и, при включённом профиле `security`, ClamAV; `web` стартует после healthy backend. `compose.prod.yaml` переводит Keycloak в режим `start`, включает TLS на nginx и закрывает `/idp/admin/` снаружи; `compose.edge.yaml` — альтернатива для сервера с общим обратным прокси нескольких проектов (отдельный Caddy в сети Docker `edge`): `web` не публикует портов и подключается к сети `edge`, Moodle публикуется тем же `web` под отдельным именем `MOODLE_EDGE_HOST` (`docs/deploy.md`, «Размещение за общим обратным прокси»). `scripts/backup.sh` снимает `pg_dump` обеих БД и архив тома вложений с контрольными суммами, `scripts/restore.sh` пересоздаёт БД и том из такой копии на том же или новом стенде.

Сервис `site-fixture` (профиль Compose `demo`) — демонстрационный стенд предложенного API сайта, `docs/integrations.md`. Backend получает адрес и токен сайта из `SITE_BASE_URL` и `SITE_TOKEN`.

`infra/moodle/compose.yaml` — отдельный локальный Moodle 4.5 (`bitnamilegacy/moodle:4.5.4-debian-12-r0` и MariaDB) со своим Compose-проектом, портом `127.0.0.1:8082` и сетью `rtk-crm-moodle`. Он не запускается вместе с CRM автоматически: `scripts/moodle-demo.sh` поднимает его, включает веб-сервисы, создаёт ограниченный сервис, токен и демо-курсы и пишет `MOODLE_BASE_URL`, `MOODLE_TOKEN`, `MOODLE_COURSE_IDS` в `.env.local`. Backend подключается к сети Moodle файлом `infra/moodle/compose.crm.yaml` (bootstrap-скрипты добавляют его сами, если `MOODLE_BASE_URL=http://moodle:8080`). Адаптер и снимки обучения — `docs/integrations.md`, раздел «Moodle».

`scripts/bootstrap-demo.sh` (bash) и `scripts/bootstrap-demo.ps1` (PowerShell 7+) выполняют одинаковые шаги: создают локальные случайные credentials в игнорируемом `.env.local`, повторно применяют `infra/keycloak/rtk-crm-realm.json` к realm, создают или находят Keycloak identities, синхронизируют их обязательные профильные атрибуты и берут реальные `sub`, а затем идемпотентно создают CRM-профили, три организации (две с разными КАМ и одну без назначенного КАМ) и явно помеченные persisted demo-записи Direction → Program и Vendor → Product. Профиль `backend-bootstrap` пересобирается перед каждым запуском, чтобы не использовать устаревший образ после Flyway-миграции. Повтор не перезаписывает существующие CRM-профили, роли или назначения; справочники разрешаются по их естественным ключам.

## Закреплённые версии

| Компонент | Версия |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.16 |
| springdoc | 2.8.17 |
| Maven Wrapper target | 3.9.16 |
| PostgreSQL image | 16.11-bookworm |
| ClamAV image | 1.4.6-debian13-slim |
| React | 19.3.0 |
| Vite | 8.3.0 |
| Node build image | 24.21.0-alpine |
| Nginx image | 1.29.2-alpine |
| Keycloak image | 26.7.4 |

Чистая сборка, запуск через Compose, применение миграций Flyway и вход через OIDC проверяются автоматически. Чистый запуск на Linux, рестарт с проверкой сохранности данных и резервное копирование/восстановление проверяются отдельно (`docs/deploy.md`).
