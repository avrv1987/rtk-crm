package ru.rtk.crm.source;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import ru.rtk.crm.source.LaborMarketRepository.SnapshotCounts;
import ru.rtk.crm.source.SourceRepository.SyncTotals;

@Component
class LaborMarketSync {
    private final LaborMarketSettingsService settings;
    private final LaborMarketClient client;
    private final LaborMarketRepository repository;
    private final LaborMarketService service;

    LaborMarketSync(
            LaborMarketSettingsService settings,
            LaborMarketClient client,
            LaborMarketRepository repository,
            LaborMarketService service
    ) {
        this.settings = settings;
        this.client = client;
        this.repository = repository;
        this.service = service;
    }

    boolean configured() {
        return settings.connection().configured();
    }

    Result run(UUID runId, UUID actorProfileId) {
        LaborMarketClient.Batch batch = client.fetch(settings.connection());
        SnapshotCounts counts = repository.saveSnapshots(batch.items(), runId, OffsetDateTime.now());
        // Сначала сохраняются снимки, затем автосопоставление: новые коды источника сразу подбираются к направлениям с
        // признаком рынка труда.
        int unmapped = service.autoMap(actorProfileId);
        List<String> parts = new ArrayList<>();
        if (batch.rejected() > 0) {
            parts.add("записей с ошибками в данных пропущено: " + batch.rejected());
        }
        if (unmapped > 0) {
            parts.add("направлений с признаком рынка труда без сопоставления: " + unmapped);
        }
        return new Result(
                new SyncTotals(batch.items().size() + batch.rejected(), counts.created(), counts.updated(), counts.skipped(), unmapped,
                        batch.rejected()),
                parts.isEmpty() ? null : String.join("; ", parts)
        );
    }

    record Result(SyncTotals totals, String message) {
    }
}
