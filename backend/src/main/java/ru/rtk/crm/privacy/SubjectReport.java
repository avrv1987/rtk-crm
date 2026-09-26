package ru.rtk.crm.privacy;

import java.time.OffsetDateTime;
import java.util.List;

import ru.rtk.crm.enrolment.LearnerPrivacyService.LearnerDisclosure;

public record SubjectReport(
        OffsetDateTime generatedAt,
        String operator,
        SubjectQuery searchTerms,
        List<Purpose> purposes,
        String sources,
        String recipients,
        List<String> retention,
        SubjectSearchResult data,
        List<LearnerDisclosure> learnerProfiles
) {
    static final String OPERATOR = "ИТ Школа РТК — оператор персональных данных CRM";
    static final String SOURCES =
            "ввод сотрудниками в карточке вуза, импорт каталога XLS/XLSX, заявки с сайта (записи источников), учётная запись Keycloak; "
                    + "для слушателей — оплаты с сайта (файл и API сайта), анкета, которую заполняет оператор зачисления вручную "
                    + "или загрузкой шаблона «Загрузка пользователей»";
    static final String RECIPIENTS =
            "третьим лицам не передаются; доступ имеют сотрудники ИТ Школы РТК в пределах роли и области данных; "
                    + "анкеты слушателей видит только оператор зачисления, он же передаёт их в LMS файлом по потоку";
    static final List<Purpose> PURPOSES = List.of(
            new Purpose(
                    "представители вузов и школ",
                    "ведение взаимодействия с вузами и школами по ИТ-программам и ИТ-продуктам",
                    "152-ФЗ, ст. 6, ч. 1, п. 7 — законные интересы оператора (рабочая гипотеза, утверждает оператор)"
            ),
            new Purpose(
                    "сотрудники — пользователи CRM",
                    "допуск к CRM, разграничение доступа, учёт выполненных действий",
                    "152-ФЗ, ст. 6, ч. 1, пп. 2 и 5 — трудовые отношения"
            ),
            new Purpose(
                    "слушатели открытых курсов",
                    "заключение и исполнение договора об оказании платных образовательных услуг, зачисление на программу "
                            + "и загрузка в LMS, оформление документа о квалификации",
                    "152-ФЗ, ст. 6, ч. 1, п. 5 — исполнение договора, стороной которого является субъект; для сведений "
                            + "ФИС ФРДО — ст. 6, ч. 1, п. 2 (рабочая гипотеза, утверждает оператор)"
            )
    );

    public record Purpose(String subjects, String purpose, String legalBasis) {
    }
}
