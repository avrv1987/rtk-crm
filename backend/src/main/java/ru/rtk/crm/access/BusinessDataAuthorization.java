package ru.rtk.crm.access;

// Бизнес-отчёты и выгрузки читают КАМ, руководитель и «Руководство». У администратора бизнес-данных нет: вместо
// пустого отчёта — 403, как у «Договоров за период» и «Людей и часов» (docs/roles.md, матрица действий).
public final class BusinessDataAuthorization {
    private BusinessDataAuthorization() {
    }

    public static void requireReader(CrmProfile profile) {
        if (profile.role() != UserRole.USER && profile.role() != UserRole.LEADER && profile.role() != UserRole.MANAGEMENT) {
            throw new AdminCrmProfileAccessDeniedException();
        }
    }
}
