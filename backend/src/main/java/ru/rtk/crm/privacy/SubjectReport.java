package ru.rtk.crm.privacy;

import java.time.OffsetDateTime;
import java.util.List;

public record SubjectReport(
        OffsetDateTime generatedAt,
        String operator,
        SubjectQuery searchTerms,
        List<Purpose> purposes,
        String sources,
        String recipients,
        List<String> retention,
        SubjectSearchResult data
) {
    static final String OPERATOR = "ИТ Школа РТК — оператор персональных данных CRM";
    static final String SOURCES =
            "ввод сотрудниками в карточке вуза, импорт каталога XLS/XLSX, заявки с сайта (записи источников), учётная запись Keycloak";
    static final String RECIPIENTS =
            "третьим лицам не передаются; доступ имеют сотрудники ИТ Школы РТК в пределах роли и области данных";
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
            )
    );

    public record Purpose(String subjects, String purpose, String legalBasis) {
    }
}
