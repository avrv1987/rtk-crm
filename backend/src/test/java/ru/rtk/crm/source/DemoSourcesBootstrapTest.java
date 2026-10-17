package ru.rtk.crm.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.bootstrap.DemoBootstrapProperties;
import ru.rtk.crm.enrolment.PaidOrder;
import ru.rtk.crm.enrolment.PaidOrderBatch;
import ru.rtk.crm.enrolment.PaidOrderParser;
import ru.rtk.crm.enrolment.PaidOrderUpload;
import ru.rtk.crm.module.ModuleProperties;

class DemoSourcesBootstrapTest {
    private static final UUID OPERATOR_ID = UUID.randomUUID();
    private static final DemoBootstrapProperties.Identity OPERATOR = new DemoBootstrapProperties.Identity(
            "enrol", "issuer", "subject-enrol", "Оператор зачисления", UserRole.USER, "open-enrolment", true, null, null);

    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final SourceRepository repository = mock(SourceRepository.class);
    private final SourceSyncService sync = mock(SourceSyncService.class);

    @Test
    void demoPaidOrdersOfTheOperatorFillOneStreamOfAMappedCourseOnce() {
        when(profiles.findActiveByIdentity("issuer", "subject-enrol"))
                .thenReturn(Optional.of(new CrmProfile(OPERATOR_ID, UserRole.USER, UUID.randomUUID(), 0)));
        when(sync.uploadPaidOrders(eq(OPERATOR_ID), any())).thenReturn(mock(PaidOrderUpload.class));

        bootstrap(true).uploadDemoPaidOrders();

        ArgumentCaptor<PaidOrderBatch> batch = ArgumentCaptor.forClass(PaidOrderBatch.class);
        verify(sync).uploadPaidOrders(eq(OPERATOR_ID), batch.capture());
        assertThat(batch.getValue().issues()).isEmpty();
        assertThat(batch.getValue().orders()).extracting(PaidOrder::course, PaidOrder::streamNumber)
                .containsOnly(tuple("Промпт-инжиниринг", 1));
        assertThat(batch.getValue().orders()).hasSize(2);
    }

    @Test
    void demoPaidOrdersAreSkippedWhenAlreadyLoadedOrTheModuleIsOff() {
        when(repository.findRecord(SourceCode.WEBSITE, SiteRecord.PAID_ORDER, DemoSourcesBootstrap.DEMO_ORDER_NUMBER))
                .thenReturn(Optional.of(mock(SourceRepository.StoredRecord.class)));
        bootstrap(true).uploadDemoPaidOrders();
        bootstrap(false).uploadDemoPaidOrders();

        verify(sync, never()).uploadPaidOrders(any(), any());
    }

    private DemoSourcesBootstrap bootstrap(boolean enrolment) {
        DemoBootstrapProperties properties = new DemoBootstrapProperties(true, false, List.of(), List.of(OPERATOR), List.of(), List.of());
        return new DemoSourcesBootstrap(properties, profiles, repository, sync, mock(LaborMarketService.class),
                new ModuleProperties(new ModuleProperties.Toggle(enrolment), new ModuleProperties.Toggle(true)),
                new PaidOrderParser(new ObjectMapper()));
    }
}
