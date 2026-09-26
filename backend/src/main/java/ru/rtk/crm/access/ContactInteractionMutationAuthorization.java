package ru.rtk.crm.access;

public final class ContactInteractionMutationAuthorization {
    private ContactInteractionMutationAuthorization() {
    }

    public static void requireCardEditor(CrmProfile profile) {
        if (profile.role() != UserRole.USER && profile.role() != UserRole.LEADER) {
            throw new ContactInteractionMutationAccessDeniedException();
        }
    }
}
