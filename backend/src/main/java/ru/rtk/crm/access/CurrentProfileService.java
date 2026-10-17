package ru.rtk.crm.access;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class CurrentProfileService {
    private static final int DISPLAY_NAME_LIMIT = 200;
    private static final int LOGIN_LIMIT = 200;

    private final UserProfileRepository userProfileRepository;

    public CurrentProfileService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    public CrmProfile requireActiveProfile(OidcUser user) {
        CrmProfile profile = requireSessionProfile(user);
        if (profile.role() == UserRole.PARTNER) {
            throw new AdminCrmProfileAccessDeniedException();
        }
        return profile;
    }

    public CrmProfile requirePartnerProfile(OidcUser user) {
        CrmProfile profile = requireSessionProfile(user);
        if (profile.role() != UserRole.PARTNER) {
            throw new AdminCrmProfileAccessDeniedException();
        }
        return profile;
    }

    // Профиль ищется по паре «издатель + subject» из подписанного ID-токена. Роль берётся из claims этого токена,
    // а не из userinfo, поэтому подмена ответа userinfo роль не меняет. Профиль без активации — отдельная
    // ошибка «ждёт активации», чтобы интерфейс не путал его с отсутствующим.
    public CrmProfile requireSessionProfile(OidcUser user) {
        String issuer = user.getIdToken().getIssuer().toString();
        String subject = user.getSubject();
        CrmProfile profile = userProfileRepository.findActiveByIdentity(issuer, subject)
                .orElseThrow(() -> userProfileRepository.isPendingActivation(issuer, subject)
                        ? new CrmProfilePendingException()
                        : new CrmProfileNotFoundException());
        Set<UserRole> granted = keycloakRoles(user);
        boolean roleGranted = profile.role() == UserRole.PARTNER
                ? granted.contains(UserRole.PARTNER)
                : UserRole.employeeRole(granted).isPresent();
        // Если роль профиля когда-то пришла из Keycloak, а в токене её больше нет (роль сняли), доступ закрывается
        // как для неизвестного профиля. Профиль, роль которого ещё не сверялась с Keycloak, остаётся работать по
        // роли из CRM до первой сверки: иначе при подключении Keycloak все остались бы без доступа.
        if (!roleGranted && userProfileRepository.isRoleFromKeycloak(profile.id())) {
            throw new CrmProfileNotFoundException();
        }
        return profile;
    }

    public static Set<UserRole> keycloakRoles(OidcUser user) {
        Map<String, Object> realmAccess = user.getIdToken().getClaimAsMap("realm_access");
        return realmAccess != null && realmAccess.get("roles") instanceof Collection<?> names
                ? UserRole.fromKeycloakRoles(names)
                : Set.of();
    }

    // Первый вход создаёт профиль в состоянии «ждёт активации»; повторный вход только обновляет логин.
    // Незнакомый сотрудник не получает данных, пока администратор не назначит команду и не активирует профиль.
    public boolean registerPendingProfile(OidcUser user) {
        String issuer = user.getIdToken().getIssuer().toString();
        String login = login(user);
        boolean created = userProfileRepository.insertPendingIfAbsent(
                UUID.randomUUID(),
                issuer,
                user.getSubject(),
                displayName(user),
                login
        );
        if (!created && login != null) {
            userProfileRepository.updateLogin(issuer, user.getSubject(), login);
        }
        return created;
    }

    private static String login(OidcUser user) {
        String value = user.getPreferredUsername();
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() > LOGIN_LIMIT ? normalized.substring(0, LOGIN_LIMIT) : normalized;
    }

    // Имя и логин усекаются до пределов колонок: слишком длинное значение из IdP не должно ломать вход.
    private static String displayName(OidcUser user) {
        String value = firstNonBlank(user.getFullName(), user.getPreferredUsername(), user.getSubject());
        return value.length() > DISPLAY_NAME_LIMIT ? value.substring(0, DISPLAY_NAME_LIMIT) : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        throw new IllegalStateException("OIDC identity has no subject");
    }
}
