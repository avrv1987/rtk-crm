package ru.rtk.crm.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionCreateRequest;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRef;
import ru.rtk.crm.interaction.InteractionKindService;
import ru.rtk.crm.interaction.InteractionService;

// «Новый цикл» выбирает маршрут через вид: без явного выбора наследует действующий вид прошлого цикла, иначе отдаёт
// выбор правилам (kindId = null), а не ищет шаблон по слову «цикл».
class CycleKindTest {
    private static final CrmProfile KAM = new CrmProfile(UUID.randomUUID(), UserRole.USER, UUID.randomUUID(), 0);
    private static final UUID PREVIOUS = UUID.randomUUID();
    private static final UUID KIND = UUID.randomUUID();

    private final InteractionService interactionService = mock(InteractionService.class);
    private final TrainingRepository repository = mock(TrainingRepository.class);
    private final InteractionKindService kindService = mock(InteractionKindService.class);
    private final TrainingService service = new TrainingService(interactionService, mock(CommandIdempotencyRepository.class),
            repository, kindService);

    @Test
    void newCycleInheritsActiveKindOfThePreviousCycle() {
        when(kindService.activeOrNull(KIND)).thenReturn(KIND);

        assertThat(startCycle(null).kindId()).isEqualTo(KIND);
    }

    @Test
    void inactiveKindIsLeftToRulesAndExplicitKindWins() {
        when(kindService.activeOrNull(KIND)).thenReturn(null);
        assertThat(startCycle(null).kindId()).isNull();

        UUID chosen = UUID.randomUUID();
        assertThat(startCycle(chosen).kindId()).isEqualTo(chosen);
    }

    private InteractionCreateRequest startCycle(UUID kindId) {
        Interaction previous = mock(Interaction.class);
        when(previous.organizationId()).thenReturn(UUID.randomUUID());
        when(previous.contactIds()).thenReturn(List.of());
        when(previous.productIds()).thenReturn(List.of());
        when(previous.kind()).thenReturn(new InteractionKindRef(KIND, "Повышение квалификации"));
        when(interactionService.get(KAM, PREVIOUS)).thenReturn(previous);
        when(repository.findCycleStart(PREVIOUS)).thenReturn(Optional.empty());
        when(repository.findNext(PREVIOUS)).thenReturn(Optional.empty());
        Interaction created = mock(Interaction.class);
        when(created.id()).thenReturn(UUID.randomUUID());
        ArgumentCaptor<InteractionCreateRequest> request = ArgumentCaptor.forClass(InteractionCreateRequest.class);
        when(interactionService.create(eq(KAM), request.capture(), any())).thenReturn(created);

        service.startCycle(KAM, PREVIOUS, new CycleStartRequest("Новый цикл", null, LocalDate.now().plusDays(1), null,
                (OffsetDateTime) null, kindId), "key-" + kindId);

        verify(repository, org.mockito.Mockito.atLeastOnce()).lockInteraction(PREVIOUS);
        return request.getValue();
    }
}
