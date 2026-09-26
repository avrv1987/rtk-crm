package ru.rtk.crm.enrolment;

import java.util.UUID;

import org.springframework.stereotype.Component;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.interaction.InteractionValidationException;

@Component
public class EnrolmentAccess {
    private final EnrolmentProperties properties;
    private final UserProfileRepository userProfileRepository;

    public EnrolmentAccess(EnrolmentProperties properties, UserProfileRepository userProfileRepository) {
        this.properties = properties;
        this.userProfileRepository = userProfileRepository;
    }

    public boolean isOperator(UUID profileId) {
        return properties.enabled() && userProfileRepository.isEnrolmentOperator(profileId);
    }

    public void requireOperator(CrmProfile profile) {
        if (!properties.enabled()) {
            throw new EnrolmentDisabledException();
        }
        if (!userProfileRepository.isEnrolmentOperator(profile.id())) {
            throw new EnrolmentAccessDeniedException();
        }
    }

    static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
