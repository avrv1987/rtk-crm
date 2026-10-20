package ru.rtk.crm.enrolment;

import java.util.UUID;

import org.springframework.stereotype.Component;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.module.ModuleDisabledException;
import ru.rtk.crm.module.ModuleProperties;

@Component
public class EnrolmentAccess {
    private final ModuleProperties modules;
    private final UserProfileRepository userProfileRepository;

    public EnrolmentAccess(ModuleProperties modules, UserProfileRepository userProfileRepository) {
        this.modules = modules;
        this.userProfileRepository = userProfileRepository;
    }

    public boolean isOperator(UUID profileId) {
        return modules.enrolmentEnabled() && userProfileRepository.isEnrolmentOperator(profileId);
    }

    public void requireOperator(CrmProfile profile) {
        if (!modules.enrolmentEnabled()) {
            throw ModuleDisabledException.enrolment();
        }
        if (!userProfileRepository.isEnrolmentOperator(profile.id())) {
            throw new EnrolmentAccessDeniedException();
        }
    }

    public void requireOperatorOrAdmin(CrmProfile profile) {
        if (profile.role() == UserRole.ADMIN && modules.enrolmentEnabled()) {
            return;
        }
        requireOperator(profile);
    }

    static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
