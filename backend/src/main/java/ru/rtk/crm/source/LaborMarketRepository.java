package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class LaborMarketRepository {
    private final JdbcClient jdbcClient;

    LaborMarketRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Одна транзакция: загрузка применяется целиком. Сравнение по ключу (код, месяц): неизменные записи не пишутся,
    // поэтому повторная загрузка не трогает fetched_at и запуск у неизменных данных; изменённые обновляются на месте.
    @Transactional
    SnapshotCounts saveSnapshots(Collection<LaborMarketItem> items, UUID runId, OffsetDateTime now) {
        Map<String, LaborMarketItem> existing = new HashMap<>();
        jdbcClient.sql("""
                        SELECT external_code, period_month, direction_name, open_vacancies, median_salary, data_source, as_of
                        FROM labor_market_snapshots
                        """)
                .query((rs, rowNum) -> new LaborMarketItem(
                        rs.getString("external_code"),
                        rs.getString("direction_name"),
                        YearMonth.from(rs.getObject("period_month", LocalDate.class)),
                        rs.getInt("open_vacancies"),
                        rs.getObject("median_salary", Integer.class),
                        rs.getString("data_source"),
                        rs.getObject("as_of", LocalDate.class)
                ))
                .list()
                .forEach(item -> existing.put(item.key(), item));
        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (LaborMarketItem item : items) {
            LaborMarketItem previous = existing.get(item.key());
            if (previous == null) {
                insert(item, runId, now);
                created++;
            } else if (previous.equals(item)) {
                skipped++;
            } else {
                update(item, runId, now);
                updated++;
            }
        }
        return new SnapshotCounts(created, updated, skipped);
    }

    private void insert(LaborMarketItem item, UUID runId, OffsetDateTime now) {
        jdbcClient.sql("""
                        INSERT INTO labor_market_snapshots (external_code, period_month, direction_name, open_vacancies, median_salary,
                                                            data_source, as_of, fetched_at, sync_run_id)
                        VALUES (:code, :month, :name, :vacancies, :salary, :dataSource, :asOf, :fetchedAt, :runId)
                        """)
                .param("code", item.directionCode())
                .param("month", item.month().atDay(1))
                .param("name", item.directionName())
                .param("vacancies", item.openVacancies())
                .param("salary", item.medianSalary())
                .param("dataSource", item.dataSource())
                .param("asOf", item.asOf())
                .param("fetchedAt", now)
                .param("runId", runId)
                .update();
    }

    private void update(LaborMarketItem item, UUID runId, OffsetDateTime now) {
        jdbcClient.sql("""
                        UPDATE labor_market_snapshots
                        SET direction_name = :name, open_vacancies = :vacancies, median_salary = :salary, data_source = :dataSource,
                            as_of = :asOf, fetched_at = :fetchedAt, sync_run_id = :runId
                        WHERE external_code = :code AND period_month = :month
                        """)
                .param("code", item.directionCode())
                .param("month", item.month().atDay(1))
                .param("name", item.directionName())
                .param("vacancies", item.openVacancies())
                .param("salary", item.medianSalary())
                .param("dataSource", item.dataSource())
                .param("asOf", item.asOf())
                .param("fetchedAt", now)
                .param("runId", runId)
                .update();
    }

    // Направление источника описывается по последнему известному месяцу: название могло меняться, актуально последнее.
    List<SourceDirection> findSourceDirections() {
        return jdbcClient.sql("""
                        SELECT s.external_code, s.direction_name
                        FROM labor_market_snapshots s
                        WHERE s.period_month = (SELECT MAX(x.period_month) FROM labor_market_snapshots x WHERE x.external_code = s.external_code)
                        ORDER BY s.direction_name, s.external_code
                        """)
                .query((rs, rowNum) -> new SourceDirection(rs.getString("external_code"), rs.getString("direction_name")))
                .list();
    }

    Map<String, Latest> findLatest() {
        Map<String, Latest> latest = new HashMap<>();
        jdbcClient.sql("""
                        SELECT s.external_code, s.period_month, s.open_vacancies, s.as_of, s.data_source
                        FROM labor_market_snapshots s
                        WHERE s.period_month = (SELECT MAX(x.period_month) FROM labor_market_snapshots x WHERE x.external_code = s.external_code)
                        """)
                .query((rs, rowNum) -> Map.entry(rs.getString("external_code"), new Latest(
                        rs.getObject("period_month", LocalDate.class),
                        rs.getInt("open_vacancies"),
                        rs.getObject("as_of", LocalDate.class),
                        rs.getString("data_source")
                )))
                .list()
                .forEach(entry -> latest.put(entry.getKey(), entry.getValue()));
        return latest;
    }

    // Только не архивные направления CRM с признаком рынка труда; сопоставление может отсутствовать (LEFT JOIN).
    List<FlaggedDirection> findFlagged() {
        return jdbcClient.sql("""
                        SELECT d.id, d.name, m.external_code, m.origin, m.version, m.updated_at, u.display_name AS updated_by_name
                        FROM directions d
                        LEFT JOIN labor_market_mappings m ON m.direction_id = d.id
                        LEFT JOIN crm_user_profiles u ON u.id = m.updated_by
                        WHERE d.labor_market = TRUE AND d.archived = FALSE
                        ORDER BY d.name, d.id
                        """)
                .query((rs, rowNum) -> new FlaggedDirection(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("external_code"),
                        rs.getString("origin"),
                        rs.getObject("version", Integer.class),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getString("updated_by_name")
                ))
                .list();
    }

    Optional<StoredMapping> findMapping(UUID directionId) {
        return jdbcClient.sql("SELECT external_code, version FROM labor_market_mappings WHERE direction_id = :id")
                .param("id", directionId)
                .query((rs, rowNum) -> new StoredMapping(rs.getString("external_code"), rs.getInt("version")))
                .optional();
    }

    boolean directionExists(UUID directionId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM directions WHERE id = :id AND archived = FALSE")
                .param("id", directionId)
                .query(Long.class)
                .single() > 0;
    }

    Optional<UUID> findDirectionIdByName(String name) {
        return jdbcClient.sql("SELECT id FROM directions WHERE name = :name").param("name", name).query(UUID.class).optional();
    }

    void markFlagged(UUID directionId) {
        jdbcClient.sql("UPDATE directions SET labor_market = TRUE WHERE id = :id").param("id", directionId).update();
    }

    void insertMapping(UUID directionId, String code, String origin, UUID actorProfileId, OffsetDateTime now) {
        jdbcClient.sql("""
                        INSERT INTO labor_market_mappings (direction_id, external_code, origin, version, updated_by, updated_at)
                        VALUES (:id, :code, :origin, 0, :actor, :now)
                        """)
                .param("id", directionId)
                .param("code", code)
                .param("origin", origin)
                .param("actor", actorProfileId)
                .param("now", now)
                .update();
    }

    // DO NOTHING: автосопоставление не затирает уже существующее, в том числе ручное, и параллельная вставка не падает.
    void insertMappingIfAbsent(UUID directionId, String code, String origin, UUID actorProfileId, OffsetDateTime now) {
        jdbcClient.sql("""
                        INSERT INTO labor_market_mappings (direction_id, external_code, origin, version, updated_by, updated_at)
                        VALUES (:id, :code, :origin, 0, :actor, :now)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", directionId)
                .param("code", code)
                .param("origin", origin)
                .param("actor", actorProfileId)
                .param("now", now)
                .update();
    }

    // Правка человеком делает происхождение ручным; версия в условии ловит параллельное изменение.
    boolean updateMapping(UUID directionId, int expectedVersion, String code, UUID actorProfileId, OffsetDateTime now) {
        return jdbcClient.sql("""
                        UPDATE labor_market_mappings
                        SET external_code = :code, origin = 'MANUAL', version = version + 1, updated_by = :actor, updated_at = :now
                        WHERE direction_id = :id AND version = :version
                        """)
                .param("id", directionId)
                .param("version", expectedVersion)
                .param("code", code)
                .param("actor", actorProfileId)
                .param("now", now)
                .update() == 1;
    }

    boolean deleteMapping(UUID directionId, int expectedVersion) {
        return jdbcClient.sql("DELETE FROM labor_market_mappings WHERE direction_id = :id AND version = :version")
                .param("id", directionId)
                .param("version", expectedVersion)
                .update() == 1;
    }

    record SnapshotCounts(int created, int updated, int skipped) {
    }

    record SourceDirection(String code, String name) {
    }

    record Latest(LocalDate month, int vacancies, LocalDate asOf, String dataSource) {
    }

    record FlaggedDirection(
            UUID id,
            String name,
            String externalCode,
            String origin,
            Integer version,
            OffsetDateTime updatedAt,
            String updatedByName
    ) {
    }

    record StoredMapping(String externalCode, int version) {
    }
}
