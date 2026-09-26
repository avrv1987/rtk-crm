package ru.rtk.crm.training;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
@RequestMapping("/api/interactions/{id}")
public class TrainingApiController {
    private final CurrentProfileService currentProfileService;
    private final TrainingService trainingService;

    public TrainingApiController(CurrentProfileService currentProfileService, TrainingService trainingService) {
        this.currentProfileService = currentProfileService;
        this.trainingService = trainingService;
    }

    @GetMapping("/teacher-trainings")
    public List<TeacherTraining> trainings(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return trainingService.trainings(currentProfileService.requireActiveProfile(user), parseUuid(id));
    }

    @PostMapping("/teacher-trainings")
    public ResponseEntity<TeacherTrainingCreated> createTraining(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) TeacherTrainingRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trainingService.createTraining(
                currentProfileService.requireActiveProfile(user), parseUuid(id), request, idempotencyKey));
    }

    @GetMapping("/cycle")
    public InteractionCycle cycle(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return trainingService.cycle(currentProfileService.requireActiveProfile(user), parseUuid(id));
    }

    @PostMapping("/cycles")
    public ResponseEntity<Interaction> startCycle(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CycleStartRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trainingService.startCycle(
                currentProfileService.requireActiveProfile(user), parseUuid(id), request, idempotencyKey));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор взаимодействия");
        }
    }
}
