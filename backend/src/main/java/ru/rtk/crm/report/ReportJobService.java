package ru.rtk.crm.report;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.ReportJobRepository.StoredJob;

@Service
public class ReportJobService {
    private static final Logger log = LoggerFactory.getLogger(ReportJobService.class);
    private static final int RECENT_JOBS_LIMIT = 20;
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ReportService reportService;
    private final ReportJobRepository repository;
    private final ReportJobExecutor executor;
    private final ReportStorage storage;
    private final ExcelReportWriter excelWriter;
    private final PdfReportWriter pdfWriter;
    private final JsonReportWriter jsonWriter;
    private final StatisticsChartWriter chartWriter;
    private final ReportProperties properties;
    private final ObjectMapper objectMapper;
    private final OffsetDateTime processStartedAt = OffsetDateTime.now();

    public ReportJobService(
            ReportService reportService,
            ReportJobRepository repository,
            ReportJobExecutor executor,
            ReportStorage storage,
            ExcelReportWriter excelWriter,
            PdfReportWriter pdfWriter,
            JsonReportWriter jsonWriter,
            StatisticsChartWriter chartWriter,
            ReportProperties properties,
            ObjectMapper objectMapper
    ) {
        this.reportService = reportService;
        this.repository = repository;
        this.executor = executor;
        this.storage = storage;
        this.excelWriter = excelWriter;
        this.pdfWriter = pdfWriter;
        this.jsonWriter = jsonWriter;
        this.chartWriter = chartWriter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ReportJobCreated submit(CrmProfile profile, ReportRequest request, String idempotencyKey) {
        String key = requiredIdempotencyKey(idempotencyKey);
        ReportRequest normalized = request.normalized();
        if (normalized.format() == null) {
            throw new InteractionValidationException("format", "Укажите формат файла: XLSX, XLS, PDF, JSON или PNG для диаграммы");
        }
        if (normalized.groupBy() == null && normalized.format() == ReportFormat.PNG) {
            throw new InteractionValidationException("format", "PNG строится только для диаграммы: укажите группировку groupBy");
        }
        if (normalized.groupBy() != null && normalized.format() != ReportFormat.PNG && normalized.format() != ReportFormat.PDF) {
            throw new InteractionValidationException("format", "Диаграмма выгружается в PNG или PDF");
        }
        String requestJson = write(normalized);
        String fingerprint = CommandFingerprint.of(objectMapper, normalized);
        var existing = repository.findByIdempotencyKey(profile.id(), key);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        UUID jobId = UUID.randomUUID();
        try {
            repository.insert(jobId, profile, key, fingerprint, requestJson, normalized, OffsetDateTime.now());
        } catch (DuplicateKeyException exception) {
            return replay(repository.findByIdempotencyKey(profile.id(), key).orElseThrow(() -> exception), fingerprint);
        }
        try {
            executor.execute(() -> run(jobId));
        } catch (TaskRejectedException exception) {
            repository.delete(jobId);
            throw ReportException.capacityExceeded();
        }
        return new ReportJobCreated(jobId);
    }

    public ReportJob get(CrmProfile profile, UUID jobId) {
        return requireGrantedJob(profile, jobId).view();
    }

    public List<ReportJob> recent(CrmProfile profile) {
        return repository.findRecentOwned(profile.id(), RECENT_JOBS_LIMIT).stream()
                .filter(job -> job.grantedTo(profile))
                .map(StoredJob::view)
                .toList();
    }

    public ReportResult result(CrmProfile profile, UUID jobId) {
        StoredJob job = requireGrantedJob(profile, jobId);
        if (job.status() != ReportJobStatus.SUCCEEDED) {
            throw ReportException.notReady();
        }
        if (job.resultStorageKey() == null || !storage.exists(job.resultStorageKey())) {
            throw ReportException.resultUnavailable();
        }
        return new ReportResult(
                storage.resultFile(job.resultStorageKey()),
                job.resultFileName(),
                job.format(),
                job.resultSizeBytes()
        );
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        if (event.getApplicationContext() instanceof WebServerApplicationContext) {
            failInterruptedJobs();
        }
    }

    public void failInterruptedJobs() {
        int failed = repository.failUnfinishedCreatedBefore(
                processStartedAt,
                "REPORT_INTERRUPTED",
                "Построение прервано перезапуском сервера; закажите отчёт заново"
        );
        storage.deletePartialFiles();
        if (failed > 0) {
            log.warn("{} unfinished report jobs were marked as failed after restart", failed);
        }
    }

    void run(UUID jobId) {
        if (!repository.claim(jobId, OffsetDateTime.now())) {
            return;
        }
        Path partial = null;
        try {
            StoredJob job = repository.findById(jobId).orElseThrow(ReportException::jobNotFound);
            CrmProfile owner = repository.findActiveProfile(job.ownerProfileId())
                    .filter(job::grantedTo)
                    .orElseThrow(ReportException::accessChanged);
            ReportRequest request = read(job.requestJson());
            UUID storageKey = UUID.randomUUID();
            partial = storage.partialFile(storageKey);
            String fileName;
            try (OutputStream output = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW)) {
                fileName = request.groupBy() == null
                        ? writeReport(jobId, owner, request, job.format(), output)
                        : writeChart(jobId, owner, request, job.format(), output);
            }
            long sizeBytes = Files.size(partial);
            storage.publish(partial, storageKey);
            partial = null;
            if (!repository.succeed(jobId, storageKey, fileName, sizeBytes, OffsetDateTime.now())) {
                storage.delete(storage.resultFile(storageKey));
            }
        } catch (ReportException exception) {
            repository.fail(jobId, exception.code(), exception.getMessage(), OffsetDateTime.now());
        } catch (IOException | RuntimeException exception) {
            log.error("Report job {} failed", jobId, exception);
            repository.fail(
                    jobId,
                    "REPORT_FAILED",
                    "Не удалось построить отчёт; повторите попытку или сузьте выборку",
                    OffsetDateTime.now()
            );
        } finally {
            if (partial != null) {
                storage.delete(partial);
            }
        }
    }

    private String writeReport(UUID jobId, CrmProfile owner, ReportRequest request, ReportFormat format, OutputStream output)
            throws IOException {
        ReportDocument document = reportService.document(owner, request);
        repository.markRowsLoaded(jobId, document.rows().size());
        render(document, format, output);
        return fileName("Отчёт_" + request.kind().fileStem(), request, document.generatedAt(), format);
    }

    private String writeChart(UUID jobId, CrmProfile owner, ReportRequest request, ReportFormat format, OutputStream output)
            throws IOException {
        StatisticsResult statistics = reportService.statistics(owner, request.statisticsRequest());
        repository.markRowsLoaded(jobId, Math.toIntExact(statistics.total()));
        if (format == ReportFormat.PNG) {
            chartWriter.writePng(statistics, request.lineChart(), output);
        } else {
            chartWriter.writePdf(statistics, request.lineChart(), output);
        }
        String stem = (request.lineChart() ? "График_" : "Диаграмма_") + request.kind().fileStem() + "_"
                + request.groupBy().title().replace(' ', '_')
                + (request.seriesBy() == null ? "" : "_линии_" + request.seriesBy().title().replace(' ', '_'));
        return fileName(stem, request, statistics.generatedAt(), format);
    }

    private void render(ReportDocument document, ReportFormat format, OutputStream output) throws IOException {
        switch (format) {
            case XLSX, XLS -> excelWriter.write(document, format, output);
            case PDF -> {
                if (document.rows().size() > properties.maxPdfRows()) {
                    throw ReportException.pdfRowLimit(properties.maxPdfRows());
                }
                pdfWriter.write(document, output);
            }
            case JSON -> jsonWriter.write(document, output);
        }
    }

    private String fileName(String stem, ReportRequest request, OffsetDateTime generatedAt, ReportFormat format) {
        String period = request.from() == null && request.to() == null
                ? "на_" + FILE_DATE.format(request.asOf() == null ? generatedAt.atZoneSameInstant(ReportRequest.ZONE) : request.asOf())
                : (request.from() == null ? "начало" : FILE_DATE.format(request.from()))
                        + "_" + (request.to() == null ? "сегодня" : FILE_DATE.format(request.to()));
        return stem + "_" + period + "." + format.extension();
    }

    private StoredJob requireGrantedJob(CrmProfile profile, UUID jobId) {
        StoredJob job = repository.findOwned(jobId, profile.id()).orElseThrow(ReportException::jobNotFound);
        if (!job.grantedTo(profile)) {
            throw ReportException.accessChanged();
        }
        return job;
    }

    private ReportJobCreated replay(StoredJob job, String fingerprint) {
        if (!job.requestFingerprint().equals(fingerprint)) {
            throw InteractionConflictException.idempotency();
        }
        return new ReportJobCreated(job.id());
    }

    private String requiredIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException("Idempotency-Key", "Не передан ключ повтора запроса Idempotency-Key");
        }
        if (value.length() > 255) {
            throw new InteractionValidationException("Idempotency-Key", "Ключ повтора запроса Idempotency-Key длиннее 255 символов");
        }
        return value;
    }

    private String write(ReportRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Report request cannot be stored", exception);
        }
    }

    private ReportRequest read(String requestJson) {
        try {
            return objectMapper.readValue(requestJson, ReportRequest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored report request cannot be read", exception);
        }
    }

    public record ReportResult(Path file, String fileName, ReportFormat format, long sizeBytes) {
    }
}
