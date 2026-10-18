package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.ManagerAt;
import ru.rtk.crm.report.ManagerWorkChange;
import ru.rtk.crm.report.ReportFilters;

@RestController
public class WorkControlApiController {
    private static final String UNASSIGNED = "UNASSIGNED";

    private final CurrentProfileService currentProfileService;
    private final WorkControlService workControlService;

    public WorkControlApiController(CurrentProfileService currentProfileService, WorkControlService workControlService) {
        this.currentProfileService = currentProfileService;
        this.workControlService = workControlService;
    }

    @GetMapping("/api/work/control")
    public WorkControlModels.Control control(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam MultiValueMap<String, String> params
    ) {
        List<String> managers = values(params, "managerId");
        ReportFilters filters = new ReportFilters(
                ids(params, "organizationId"),
                ids(params, "directionId"),
                false,
                ids(params, "programId"),
                false,
                ids(params, "productId"),
                false,
                ids(managers.stream().filter(value -> !UNASSIGNED.equals(value)).toList(), "managerId"),
                managers.contains(UNASSIGNED),
                values(params, "stage"),
                null
        ).withChange(change(params)).withManagerAt(managerAt(params));
        return workControlService.control(
                currentProfileService.requireActiveProfile(user),
                filters,
                date(params, "from"),
                date(params, "to")
        );
    }

    private static List<String> values(MultiValueMap<String, String> params, String name) {
        return params.getOrDefault(name, List.of()).stream().filter(value -> !value.isBlank()).toList();
    }

    private static List<UUID> ids(MultiValueMap<String, String> params, String name) {
        return ids(values(params, name), name);
    }

    private static List<UUID> ids(List<String> values, String name) {
        List<UUID> ids = new ArrayList<>();
        for (String value : values) {
            try {
                ids.add(UUID.fromString(value.strip()));
            } catch (IllegalArgumentException exception) {
                throw new InteractionValidationException(name, "Некорректный идентификатор");
            }
        }
        return ids;
    }

    private static LocalDate date(MultiValueMap<String, String> params, String name) {
        List<String> values = values(params, name);
        if (values.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(values.getFirst().strip());
        } catch (DateTimeParseException exception) {
            throw new InteractionValidationException(name, "Укажите дату в формате ГГГГ-ММ-ДД");
        }
    }

    // mode=PERIOD — режим «За период» (КАМ на конец периода), иначе «Сейчас».
    private static ManagerAt managerAt(MultiValueMap<String, String> params) {
        List<String> values = values(params, "mode");
        if (values.isEmpty() || "NOW".equals(values.getFirst().strip())) {
            return ManagerAt.CURRENT;
        }
        if ("PERIOD".equals(values.getFirst().strip())) {
            return ManagerAt.PERIOD_END;
        }
        throw new InteractionValidationException("mode", "Режим «Контроля» — NOW или PERIOD");
    }

    private static ManagerWorkChange change(MultiValueMap<String, String> params) {
        List<String> values = values(params, "change");
        if (values.isEmpty()) {
            return null;
        }
        try {
            return ManagerWorkChange.valueOf(values.getFirst().strip());
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("change", "Такой отбор изменений не поддерживается");
        }
    }
}
