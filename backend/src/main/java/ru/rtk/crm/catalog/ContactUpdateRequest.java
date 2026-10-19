package ru.rtk.crm.catalog;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.rtk.crm.web.InputRules;

public record ContactUpdateRequest(
        Integer version,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 200) String position,
        @Email(regexp = InputRules.LOGIN_EMAIL_REGEX, message = InputRules.LOGIN_EMAIL_RULE) @Size(max = 254) String email,
        @Size(max = 50) String phone,
        ContactRole role,
        boolean primary,
        boolean inactive,
        boolean confirm
) {
}
