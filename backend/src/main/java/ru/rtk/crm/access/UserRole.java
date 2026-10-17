package ru.rtk.crm.access;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public enum UserRole {
    USER,
    LEADER,
    ADMIN,
    MANAGEMENT,
    PARTNER;

    private static final List<UserRole> EMPLOYEE_PRIORITY = List.of(ADMIN, MANAGEMENT, LEADER, USER);

    public String keycloakRole() {
        return "crm-" + name().toLowerCase(Locale.ROOT);
    }

    public static Set<UserRole> fromKeycloakRoles(Collection<?> names) {
        Set<UserRole> roles = EnumSet.noneOf(UserRole.class);
        for (UserRole role : values()) {
            if (names.contains(role.keycloakRole())) {
                roles.add(role);
            }
        }
        return roles;
    }

    public static Optional<UserRole> employeeRole(Set<UserRole> roles) {
        return EMPLOYEE_PRIORITY.stream().filter(roles::contains).findFirst();
    }
}
