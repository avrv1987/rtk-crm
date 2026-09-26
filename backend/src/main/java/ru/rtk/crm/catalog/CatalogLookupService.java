package ru.rtk.crm.catalog;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;

@Service
public class CatalogLookupService {
    private final CatalogRepository catalogRepository;

    public CatalogLookupService(CatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    @Transactional(readOnly = true)
    public CatalogPage listPrograms(CrmProfile profile, CatalogQuery query, CatalogEntryState state) {
        return hasBusinessCatalogScope(profile)
                ? catalogRepository.findPrograms(query, state)
                : empty(query);
    }

    @Transactional(readOnly = true)
    public CatalogPage listProducts(CrmProfile profile, CatalogQuery query, CatalogEntryState state) {
        return hasBusinessCatalogScope(profile)
                ? catalogRepository.findProducts(query, state)
                : empty(query);
    }

    @Transactional(readOnly = true)
    public CatalogPage listDirections(CrmProfile profile, CatalogQuery query, CatalogEntryState state) {
        return hasBusinessCatalogScope(profile)
                ? catalogRepository.findDirections(query, state)
                : empty(query);
    }

    private boolean hasBusinessCatalogScope(CrmProfile profile) {
        return profile.role() == UserRole.MANAGEMENT || (profile.teamId() != null && profile.role() != UserRole.ADMIN);
    }

    private CatalogPage empty(CatalogQuery query) {
        return new CatalogPage(List.of(), query.page(), query.size(), 0);
    }
}
