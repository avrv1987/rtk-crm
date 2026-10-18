package ru.rtk.crm.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.rtk.crm.mail.MailNotifications;

// Письма «Запроса вузу» без модуля почты и в момент коммита: без бина ничего не регистрируется, с бином письмо
// ставится только в afterCommit, а сбой почты не выходит наружу.
class PartnerRequestMailOffTest {
    @BeforeEach
    void startSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void withoutMailModuleNothingIsRegistered() {
        service(new StaticListableBeanFactory()).afterCommit(mail -> {
            throw new AssertionError("Без модуля почты письма не ставятся");
        });
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void withMailModuleLetterIsQueuedOnlyAfterCommitAndFailureIsSwallowed() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("mailNotifications", mock(MailNotifications.class));
        List<String> calls = new ArrayList<>();
        PartnerRequestService service = service(beans);
        service.afterCommit(mail -> calls.add("queued"));
        service.afterCommit(mail -> {
            throw new IllegalStateException("smtp down");
        });
        assertThat(calls).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
        assertThat(calls).containsExactly("queued");
    }

    private static PartnerRequestService service(StaticListableBeanFactory beans) {
        return new PartnerRequestService(null, null, null, null, null, null, null, null, null,
                beans.getBeanProvider(MailNotifications.class), mock(PlatformTransactionManager.class));
    }
}
