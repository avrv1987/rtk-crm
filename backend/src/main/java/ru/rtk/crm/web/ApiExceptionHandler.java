package ru.rtk.crm.web;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import ru.rtk.crm.access.AccountSyncException;
import ru.rtk.crm.access.ContactInteractionMutationAccessDeniedException;
import ru.rtk.crm.access.CrmProfileNotFoundException;
import ru.rtk.crm.access.CrmProfilePendingException;
import ru.rtk.crm.access.KeycloakAccountConflictException;
import ru.rtk.crm.access.TeamNotFoundException;
import ru.rtk.crm.access.AdminCrmProfileAccessDeniedException;
import ru.rtk.crm.access.AdminCrmProfileNotFoundException;
import ru.rtk.crm.catalog.CatalogChangeAccessDeniedException;
import ru.rtk.crm.catalog.CatalogEntryNotFoundException;
import ru.rtk.crm.catalog.ContactNotFoundException;
import ru.rtk.crm.catalog.InvalidOrganizationQueryException;
import ru.rtk.crm.catalog.OrganizationAssignmentAccessDeniedException;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalogimport.CatalogImportAccessDeniedException;
import ru.rtk.crm.catalogimport.CatalogImportJobNotFoundException;
import ru.rtk.crm.catalogimport.CatalogImportNotFoundException;
import ru.rtk.crm.enrolment.EnrolmentAccessDeniedException;
import ru.rtk.crm.enrolment.EnrolmentDisabledException;
import ru.rtk.crm.enrolment.EnrolmentNotFoundException;
import ru.rtk.crm.enrolment.LearnerValidationException;
import ru.rtk.crm.attachment.AttachmentBindingException;
import ru.rtk.crm.attachment.AttachmentDeletionForbiddenException;
import ru.rtk.crm.attachment.AttachmentNotFoundException;
import ru.rtk.crm.attachment.AttachmentTooLargeException;
import ru.rtk.crm.attachment.AttachmentValidationException;
import ru.rtk.crm.enrolment.LearnerNotFoundException;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionIssueNotFoundException;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.ProductAgreementNotFoundException;
import ru.rtk.crm.interaction.WorkflowTemplateAccessDeniedException;
import ru.rtk.crm.interaction.WorkflowTemplateNotFoundException;
import ru.rtk.crm.security.RequestId;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(CrmProfileNotFoundException.class)
    public ResponseEntity<ApiError> crmProfileNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("CRM_PROFILE_REQUIRED", "Нужен активный профиль CRM", RequestId.from(request)));
    }

    @ExceptionHandler(CrmProfilePendingException.class)
    public ResponseEntity<ApiError> crmProfilePending(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(
                        "CRM_PROFILE_PENDING",
                        "Профиль CRM ожидает активации администратором",
                        RequestId.from(request)
                ));
    }

    @ExceptionHandler(TeamNotFoundException.class)
    public ResponseEntity<ApiError> teamNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Команда не найдена", RequestId.from(request)));
    }

    @ExceptionHandler(AdminCrmProfileNotFoundException.class)
    public ResponseEntity<ApiError> adminCrmProfileNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Профиль CRM не найден", RequestId.from(request)));
    }

    @ExceptionHandler(AdminCrmProfileAccessDeniedException.class)
    public ResponseEntity<ApiError> adminCrmProfileAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Доступ запрещён", RequestId.from(request)));
    }

    @ExceptionHandler(AccountSyncException.class)
    public ResponseEntity<ApiError> accountSyncFailed(AccountSyncException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("ACCOUNT_SYNC_FAILED", exception.getMessage(), RequestId.from(request)));
    }

    @ExceptionHandler(KeycloakAccountConflictException.class)
    public ResponseEntity<ApiError> keycloakAccountConflict(KeycloakAccountConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(exception.code(), exception.getMessage(), RequestId.from(request)));
    }

    @ExceptionHandler(OrganizationNotFoundException.class)
    public ResponseEntity<ApiError> organizationNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Вуз не найден или недоступен", RequestId.from(request)));
    }

    @ExceptionHandler(ContactNotFoundException.class)
    public ResponseEntity<ApiError> contactNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Контакт не найден или недоступен", RequestId.from(request)));
    }

    @ExceptionHandler(LearnerNotFoundException.class)
    public ResponseEntity<ApiError> learnerNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Слушатель не найден", RequestId.from(request)));
    }

    @ExceptionHandler(OrganizationAssignmentAccessDeniedException.class)
    public ResponseEntity<ApiError> organizationAssignmentAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Доступ запрещён", RequestId.from(request)));
    }

    @ExceptionHandler(CatalogEntryNotFoundException.class)
    public ResponseEntity<ApiError> catalogEntryNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Запись справочника не найдена", RequestId.from(request)));
    }

    @ExceptionHandler(CatalogChangeAccessDeniedException.class)
    public ResponseEntity<ApiError> catalogChangeAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Доступ запрещён", RequestId.from(request)));
    }

    @ExceptionHandler(InteractionNotFoundException.class)
    public ResponseEntity<ApiError> interactionNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Взаимодействие не найдено или недоступно", RequestId.from(request)));
    }

    @ExceptionHandler(InteractionIssueNotFoundException.class)
    public ResponseEntity<ApiError> interactionIssueNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Запись не найдена в этой работе", RequestId.from(request)));
    }

    @ExceptionHandler(ProductAgreementNotFoundException.class)
    public ResponseEntity<ApiError> productAgreementNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Продукт не найден в этом взаимодействии", RequestId.from(request)));
    }

    @ExceptionHandler(WorkflowTemplateNotFoundException.class)
    public ResponseEntity<ApiError> workflowTemplateNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Шаблон процесса не найден", RequestId.from(request)));
    }

    @ExceptionHandler(AttachmentNotFoundException.class)
    public ResponseEntity<ApiError> attachmentNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Файл не найден или недоступен", RequestId.from(request)));
    }

    @ExceptionHandler({CatalogImportNotFoundException.class, CatalogImportJobNotFoundException.class})
    public ResponseEntity<ApiError> catalogImportNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Импорт не найден", RequestId.from(request)));
    }

    @ExceptionHandler(CatalogImportAccessDeniedException.class)
    public ResponseEntity<ApiError> catalogImportAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Доступ запрещён", RequestId.from(request)));
    }

    @ExceptionHandler(EnrolmentDisabledException.class)
    public ResponseEntity<ApiError> enrolmentDisabled(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(
                "NOT_FOUND", "Раздел «Зачисление» недоступен: модуль «Слушатели» выключен", RequestId.from(request)
        ));
    }

    @ExceptionHandler(EnrolmentAccessDeniedException.class)
    public ResponseEntity<ApiError> enrolmentAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(
                "FORBIDDEN", "Загрузка оплат и раздел «Зачисление» доступны только оператору зачисления", RequestId.from(request)
        ));
    }

    @ExceptionHandler(EnrolmentNotFoundException.class)
    public ResponseEntity<ApiError> enrolmentObjectNotFound(EnrolmentNotFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", exception.getMessage(), RequestId.from(request)));
    }

    @ExceptionHandler(LearnerValidationException.class)
    public ResponseEntity<ApiError> invalidLearnerProfile(LearnerValidationException exception, HttpServletRequest request) {
        return validationError(request, exception.fieldErrors());
    }

    @ExceptionHandler(ContactInteractionMutationAccessDeniedException.class)
    public ResponseEntity<ApiError> contactInteractionMutationAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Доступ запрещён", RequestId.from(request)));
    }

    @ExceptionHandler(AttachmentDeletionForbiddenException.class)
    public ResponseEntity<ApiError> attachmentDeletionForbidden(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Удалить документ может автор или руководитель команды", RequestId.from(request)));
    }

    @ExceptionHandler(WorkflowTemplateAccessDeniedException.class)
    public ResponseEntity<ApiError> workflowTemplateAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Доступ запрещён", RequestId.from(request)));
    }

    @ExceptionHandler(InvalidOrganizationQueryException.class)
    public ResponseEntity<ApiError> invalidOrganizationQuery(
            InvalidOrganizationQueryException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.badRequest().body(new ApiError(
                "VALIDATION_ERROR",
                "Проверьте введённые данные",
                RequestId.from(request),
                Map.of(exception.field(), exception.getMessage()),
                null
        ));
    }

    @ExceptionHandler(InteractionValidationException.class)
    public ResponseEntity<ApiError> invalidInteractionRequest(
            InteractionValidationException exception,
            HttpServletRequest request
    ) {
        return validationError(request, Map.of(exception.field(), exception.getMessage()));
    }

    @ExceptionHandler(AttachmentValidationException.class)
    public ResponseEntity<ApiError> invalidAttachmentRequest(
            AttachmentValidationException exception,
            HttpServletRequest request
    ) {
        return validationError(request, Map.of(exception.field(), exception.getMessage()));
    }

    @ExceptionHandler(AttachmentBindingException.class)
    public ResponseEntity<ApiError> invalidAttachmentBinding(
            AttachmentBindingException exception,
            HttpServletRequest request
    ) {
        return validationError(request, Map.of("attachmentIds", exception.getMessage()));
    }

    @ExceptionHandler({AttachmentTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiError> attachmentTooLarge(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of("PAYLOAD_TOO_LARGE", "Файл больше допустимого размера", RequestId.from(request)));
    }

    @ExceptionHandler(InteractionConflictException.class)
    public ResponseEntity<ApiError> interactionConflict(
            InteractionConflictException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                exception.code(),
                exception.getMessage(),
                RequestId.from(request),
                null,
                exception.currentVersion()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return validationError(request, fieldErrors);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class
    })
    public ResponseEntity<ApiError> invalidRequestFormat(Exception exception, HttpServletRequest request) {
        Map<String, String> fieldErrors = switch (exception) {
            case MethodArgumentTypeMismatchException mismatch -> Map.of(
                    mismatch.getName(),
                    "Значение в неверном формате"
            );
            case MissingRequestHeaderException missingHeader -> Map.of(
                    missingHeader.getHeaderName(),
                    "Не передан обязательный заголовок запроса"
            );
            case MissingServletRequestParameterException missingParameter -> Map.of(
                    missingParameter.getParameterName(),
                    "Не передан обязательный параметр запроса"
            );
            case MissingServletRequestPartException missingPart -> Map.of(
                    missingPart.getRequestPartName(),
                    "Не передана обязательная часть запроса"
            );
            default -> Map.of("body", "Данные запроса в неверном формате");
        };
        return validationError(request, fieldErrors);
    }

    private ResponseEntity<ApiError> validationError(HttpServletRequest request, Map<String, String> fieldErrors) {
        return ResponseEntity.badRequest().body(new ApiError(
                "VALIDATION_ERROR",
                "Проверьте введённые данные",
                RequestId.from(request),
                fieldErrors,
                null
        ));
    }
}
