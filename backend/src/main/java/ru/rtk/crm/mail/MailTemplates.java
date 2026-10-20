package ru.rtk.crm.mail;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import ru.rtk.crm.interaction.InteractionIssueKind;
import ru.rtk.crm.max.MaxDigestFormatter;
import ru.rtk.crm.work.WorkModels.ManagerIndicators;
import ru.rtk.crm.work.WorkModels.ReminderDigest;
import ru.rtk.crm.work.WorkModels.TeamIndicators;
import ru.rtk.crm.work.WorkModels.TeamSummary;
import ru.rtk.crm.work.WorkModels.TeamsSummary;

// Тексты писем. Каждый шаблон принимает только структурные поля: названия вузов и команд, номера договоров, даты и
// числа. Тексты шагов, комментарии и описания рисков пишут люди, там бывают ФИО и телефоны, поэтому в письма они не
// попадают — за подробностями письмо ведёт ссылкой в CRM, где действуют права.
@Component
@MailEnabled
public class MailTemplates {
    static final int LIST_LINES = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    static final String SETTINGS_HASH = "#/mail-settings";

    private final MailProperties properties;

    public MailTemplates(MailProperties properties) {
        this.properties = properties;
    }

    public record Letter(String subject, String text, String html) {
    }

    Letter invitation(String login, String link, Duration ttl, boolean newAccount) {
        long hours = ttl.toHours();
        return letter(newAccount ? "Доступ в CRM ИТ Школы РТК" : "Новый пароль для входа в CRM ИТ Школы РТК", List.of(
                newAccount ? "Для вас открыт доступ в CRM ИТ Школы РТК." : "Администратор выдал вам новый пароль для входа в CRM.",
                "Логин: " + login,
                "Откройте ссылку и нажмите «Показать пароль»: CRM покажет временный пароль один раз. При первом входе задайте свой пароль.",
                "Ссылка действует " + hours + " ч и открывается один раз. Если письмо пришло по ошибке, просто удалите его."
        ), "Получить пароль", link, false);
    }

    // null — сказать нечего: пустая сводка не отправляется, как и в MAX.
    Letter digest(ReminderDigest digest) {
        String text = MaxDigestFormatter.digest(digest);
        return text == null ? null : letter("Работа на сегодня", List.of(text.split("\n", -1)), "Открыть «Мою работу»", "#/work", true);
    }

    Letter assignment(String organization, UUID organizationId) {
        return letter("Вам назначен вуз «" + organization + "»", List.of("Вам назначен вуз «" + organization + "»."),
                "Открыть в CRM", organizationHash(organizationId), true);
    }

    Letter agreementCorrection(String number, String organization, UUID organizationId) {
        return letter("Договор № " + number + " вернули на доработку",
                List.of("Договор № " + number + " с «" + organization + "» вернули на доработку."),
                "Открыть в CRM", organizationHash(organizationId), true);
    }

    Letter agreementExpiry(String number, String organization, LocalDate validUntil, LocalDate today, UUID organizationId) {
        long days = validUntil.toEpochDay() - today.toEpochDay();
        return letter("Срок договора № " + number + " истекает " + DATE.format(validUntil),
                List.of("Срок договора № " + number + " с «" + organization + "» истекает " + DATE.format(validUntil)
                        + (days == 0 ? " (сегодня)." : " (через " + days + " дн.)."), "Пора начать продление."),
                "Открыть в CRM", organizationHash(organizationId), true);
    }

    Letter issueAssigned(InteractionIssueKind kind, String riskLevel, String organization, LocalDate dueOn, UUID organizationId) {
        List<String> lines = new ArrayList<>();
        lines.add("Вас назначили ответственным: " + kind.lowerLabel() + " по вузу «" + organization + "».");
        if (riskLevel != null) {
            lines.add("Уровень риска: " + riskLevel + ".");
        }
        if (dueOn != null) {
            lines.add("Срок: " + DATE.format(dueOn) + ".");
        }
        lines.add("Описание — в карточке вуза, во вкладке «Проблемы и риски».");
        return letter(kind.label() + " по вузу «" + organization + "»: вы ответственный", lines, "Открыть в CRM",
                organizationHash(organizationId), true);
    }

    Letter issueResolved(InteractionIssueKind kind, String organization, UUID organizationId) {
        return letter("Решение по вузу «" + organization + "»",
                List.of("По вузу «" + organization + "» приняли решение по отмеченной вами записи (" + kind.lowerLabel() + ").",
                        "Решение — в карточке вуза, во вкладке «Проблемы и риски»."),
                "Открыть в CRM", organizationHash(organizationId), true);
    }

    Letter partnerRequest(String organization) {
        return letter("Новый запрос от ИТ Школы РТК",
                List.of("ИТ Школа РТК отправила запрос по «" + organization + "».", "Ответить можно в кабинете вуза."),
                "Открыть кабинет вуза", "#/partner", false);
    }

    // Причину возврата пишет КАМ, в ней бывают ФИО и контакты: в письме только факт, причина — в кабинете вуза.
    Letter partnerReturned(String organization) {
        return letter("Ответ возвращён на доработку: «" + organization + "»",
                List.of("ИТ Школа РТК вернула ответ по «" + organization + "» на доработку.",
                        "Причина возврата и запрос — в кабинете вуза."),
                "Открыть кабинет вуза", "#/partner", false);
    }

    // Только факт приёмки: текст запроса и ответа, ФИО КАМ в письмо не идут. Внизу — ссылка на настройку темы:
    // это письмо координатор может отключить.
    Letter partnerAccepted(String organization) {
        return letter("Ответ принят: «" + organization + "»",
                List.of("ИТ Школа РТК приняла ответ по «" + organization + "».", "Запрос и ответ — в кабинете вуза."),
                "Открыть кабинет вуза", "#/partner", true);
    }

    // Ссылка ведёт в карточку взаимодействия: там вкладка «Запросы вузу» с ответом, который КАМ принимает или возвращает.
    Letter partnerReply(String organization, UUID organizationId, UUID interactionId) {
        return letter("Координатор ответил: «" + organization + "»",
                List.of("Координатор вуза «" + organization + "» ответил на запрос.",
                        "Ответ — в карточке взаимодействия, во вкладке «Запросы вузу»."),
                "Открыть в CRM", organizationHash(organizationId) + "/" + interactionId, true);
    }

    // Без текста плана: в нём могут быть ПДн, а сам план сотрудник смотрит в CRM.
    Letter agentPlan(String organization, UUID planId, OffsetDateTime expiresAt) {
        return letter("План ИИ-агента ждёт подтверждения: «" + organization + "»",
                List.of("ИИ-агент подготовил план по вузу «" + organization + "». Данные CRM не изменены.",
                        "Проверьте план в CRM и нажмите «Записать» или «Отклонить». План действует до "
                                + TIME.format(expiresAt.atZoneSameInstant(MailProperties.ZONE)) + " (Москва)."),
                "Открыть план", "#/agent-plans/" + planId, true);
    }

    Letter siteRequest(String organization, UUID organizationId) {
        return letter("Новая заявка с сайта: «" + organization + "»",
                List.of("Новая заявка с сайта: «" + organization + "». Разберите её в CRM."),
                "Открыть в CRM", organizationHash(organizationId), true);
    }

    Letter unassigned(String previousOwner, String reason, List<String> organizations) {
        List<String> lines = new ArrayList<>();
        lines.add((previousOwner == null ? "Сотрудник" : previousOwner) + " " + reason + ", и вузы остались без КАМ ("
                + organizations.size() + "):");
        organizations.stream().limit(LIST_LINES).forEach(name -> lines.add("• " + name));
        if (organizations.size() > LIST_LINES) {
            lines.add("… и ещё " + (organizations.size() - LIST_LINES));
        }
        lines.add("Назначьте им КАМ в CRM.");
        return letter("Вузы остались без КАМ: " + organizations.size(), lines, "Открыть «Контроль»", "#/control", true);
    }

    // null — в команде нет ни одной позиции: пустую сводку не шлём.
    Letter weeklyLeader(TeamIndicators indicators) {
        long organizations = 0;
        long interactions = 0;
        long overdue = 0;
        long withoutStep = 0;
        long stuck = 0;
        long risk = 0;
        for (ManagerIndicators manager : indicators.managers()) {
            organizations += manager.organizations();
            interactions += manager.interactions();
            overdue += manager.overdue();
            withoutStep += manager.withoutNextStep();
            stuck += manager.stuck();
            risk += manager.atRisk();
        }
        if (organizations + interactions == 0) {
            return null;
        }
        return letter("Неделя команды: просрочено " + overdue, List.of(
                "Сводка по команде на понедельник.",
                "Вузов: " + organizations + " · без КАМ: " + indicators.unassignedOrganizations(),
                "Взаимодействий: " + interactions + " · просрочено: " + overdue + " · без шага: " + withoutStep,
                "Без движения больше " + indicators.stuckDays() + " дн.: " + stuck + " · с риском: " + risk
        ), "Открыть «Контроль»", "#/control", true);
    }

    Letter weeklyManagement(TeamsSummary summary) {
        TeamSummary total = summary.total();
        if (total == null || total.organizations() + total.interactions() == 0) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Сводка по командам на понедельник.");
        lines.add("Всего: вузов " + total.organizations() + ", без КАМ " + total.unassignedOrganizations() + ", взаимодействий "
                + total.interactions() + ", просрочено " + total.overdue() + ", без шага " + total.withoutNextStep()
                + ", с риском " + total.atRisk() + ".");
        summary.teams().stream().limit(LIST_LINES).forEach(team -> lines.add("• " + team.teamName() + ": вузов " + team.organizations()
                + ", просрочено " + team.overdue() + ", без шага " + team.withoutNextStep() + ", с риском " + team.atRisk()));
        if (summary.teams().size() > LIST_LINES) {
            lines.add("… и ещё команд: " + (summary.teams().size() - LIST_LINES));
        }
        return letter("Неделя по командам: просрочено " + total.overdue(), lines, "Открыть сводку", "#/control", true);
    }

    Letter sourceFailure(String source, String errorCode) {
        return letter("Сбой источника: " + source, List.of(
                "Синхронизация источника «" + source + "» завершилась ошибкой" + (errorCode == null ? "." : " (код " + errorCode + ")."),
                "Подробности и повтор — в разделе «Администрирование → Источники данных»."
        ), "Открыть «Источники данных»", "#/admin/sources", true);
    }

    Letter pendingActivation() {
        return letter("Сотрудник ждёт активации", List.of(
                "Новый сотрудник вошёл в CRM и попросил активировать профиль.",
                "Назначьте роль и команду в разделе «Администрирование → Сотрудники»."
        ), "Открыть «Сотрудники»", "#/admin/employees?status=PENDING", true);
    }

    // null — всё в порядке: пустая сводка не отправляется.
    Letter adminDaily(MailEventRepository.AdminCounts counts) {
        if (counts.total() == 0) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Что требует внимания администратора:");
        if (counts.pendingProfiles() > 0) {
            lines.add("• сотрудников ждут активации: " + counts.pendingProfiles());
        }
        if (counts.failedRuns() > 0) {
            lines.add("• сбоев синхронизации источников за сутки: " + counts.failedRuns());
        }
        if (counts.crmErrors() > 0) {
            lines.add("• событий для CRM заказчика с ошибкой: " + counts.crmErrors());
        }
        if (counts.mailErrors() > 0) {
            lines.add("• писем с ошибкой отправки: " + counts.mailErrors());
        }
        if (counts.loginDenials() > 0) {
            lines.add("• отказов во входе за сутки: " + counts.loginDenials());
        }
        return letter("Сводка администратора", lines, "Открыть «Администрирование»", "#/admin/overview", true);
    }

    Letter test() {
        return letter("Тестовое письмо CRM ИТ Школы РТК", List.of(
                "Это тестовое письмо: настройки почты в CRM работают.",
                "Отвечать на него не нужно."
        ), "Открыть CRM", "#/", false);
    }

    Letter subjectReport() {
        return letter("Сведения об обработке ваших персональных данных", List.of(
                "По вашему запросу направляем сведения об обработке ваших персональных данных в CRM ИТ Школы РТК.",
                "Сведения — во вложенном файле PDF."
        ), null, null, false);
    }

    static String organizationHash(UUID organizationId) {
        return "#/organizations/" + organizationId;
    }

    // Текст и HTML собираются из одних строк. В HTML всё экранируется: названия вузов приходят из справочников и с сайта.
    private Letter letter(String subject, List<String> lines, String linkLabel, String hash, boolean settingsFooter) {
        String url = hash == null ? null : hash.startsWith("http") ? hash : properties.crmUrl(hash);
        String settingsUrl = properties.crmUrl(SETTINGS_HASH);
        StringBuilder text = new StringBuilder(String.join("\n", lines));
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"ru\"><body style=\"font-family:Arial,sans-serif;"
                + "font-size:15px;line-height:1.5;color:#1f2937\">");
        for (String line : lines) {
            html.append(line.isEmpty() ? "<br>" : "<p style=\"margin:0 0 8px\">" + HtmlUtils.htmlEscape(line) + "</p>");
        }
        if (url != null) {
            text.append("\n\n").append(linkLabel).append(": ").append(url);
            html.append("<p style=\"margin:16px 0\"><a href=\"").append(HtmlUtils.htmlEscape(url))
                    .append("\" style=\"background:#7700ff;color:#ffffff;padding:10px 16px;border-radius:6px;text-decoration:none\">")
                    .append(HtmlUtils.htmlEscape(linkLabel)).append("</a></p>");
        }
        text.append("\n\n—\nПисьмо отправлено автоматически CRM ИТ Школы РТК, отвечать на него не нужно.");
        html.append("<hr style=\"border:none;border-top:1px solid #e5e7eb;margin:24px 0 8px\"><p style=\"font-size:13px;color:#6b7280\">")
                .append("Письмо отправлено автоматически CRM ИТ Школы РТК, отвечать на него не нужно.");
        if (settingsFooter) {
            text.append("\nНастроить уведомления: ").append(settingsUrl);
            html.append(" <a href=\"").append(HtmlUtils.htmlEscape(settingsUrl)).append("\">Настроить уведомления</a>");
        }
        html.append("</p></body></html>");
        return new Letter(subject.length() > 300 ? subject.substring(0, 300) : subject, text.toString(), html.toString());
    }
}
