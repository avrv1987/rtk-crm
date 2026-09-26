package ru.rtk.crm.enrolment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.PersonalDataStatus;

@Repository
public class LearnerRepository {
    private static final String SELECT = """
            SELECT id, key_version, fields, missing_fields, personal_data_status, version, created_at, updated_at
            FROM learners
            """;
    private static final String ANONYMIZE = """
            UPDATE learners
            SET fields = NULL, key_version = NULL, email_hmac = NULL, phone_hmac = NULL, snils_hmac = NULL, name_hmac = NULL,
                last_name_hmac = NULL, personal_data_status = 'ANONYMIZED', anonymized_at = :now, version = version + 1,
                updated_at = :now
            WHERE personal_data_status <> 'ANONYMIZED' AND
            """;
    private static final TypeReference<EnumMap<LearnerField, String>> FIELDS = new TypeReference<>() {
    };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    LearnerRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    void insert(LearnerWrite learner, UUID createdBy, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO learners (
                    id, key_version, fields, email_hmac, phone_hmac, snils_hmac, name_hmac, last_name_hmac, missing_fields,
                    created_by, created_at, updated_at
                ) VALUES (
                    :id, :keyVersion, :fields, :emailHmac, :phoneHmac, :snilsHmac, :nameHmac, :lastNameHmac, :missingFields,
                    :createdBy, :now, :now
                )
                """)
                .params(values(learner))
                .param("createdBy", createdBy)
                .param("now", now)
                .update();
    }

    boolean update(LearnerWrite learner, int expectedVersion, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE learners
                SET key_version = :keyVersion, fields = :fields, email_hmac = :emailHmac, phone_hmac = :phoneHmac,
                    snils_hmac = :snilsHmac, name_hmac = :nameHmac, last_name_hmac = :lastNameHmac,
                    missing_fields = :missingFields, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion AND personal_data_status = 'ACTIVE'
                """)
                .params(values(learner))
                .param("expectedVersion", expectedVersion)
                .param("now", now)
                .update() == 1;
    }

    boolean reencrypt(LearnerWrite learner, int expectedVersion) {
        return jdbcClient.sql("""
                UPDATE learners
                SET key_version = :keyVersion, fields = :fields, email_hmac = :emailHmac, phone_hmac = :phoneHmac,
                    snils_hmac = :snilsHmac, name_hmac = :nameHmac, last_name_hmac = :lastNameHmac
                WHERE id = :id AND version = :expectedVersion AND personal_data_status <> 'ANONYMIZED'
                """)
                .params(values(learner))
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    Optional<LearnerRow> find(UUID id) {
        return jdbcClient.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    Optional<LearnerRow> lock(UUID id) {
        return jdbcClient.sql(SELECT + " WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    List<LearnerRow> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(SELECT + " WHERE id IN (:ids)")
                .param("ids", ids)
                .query(this::mapRow)
                .list();
    }

    void delete(UUID id) {
        jdbcClient.sql("DELETE FROM learners WHERE id = :id")
                .param("id", id)
                .update();
    }

    List<LearnerRow> findByFingerprints(Map<Fingerprint, Set<String>> fingerprints, int limit) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        fingerprints.forEach((kind, values) -> {
            if (!values.isEmpty()) {
                conditions.add(kind.column + " IN (:" + kind.name() + ")");
                params.put(kind.name(), values);
            }
        });
        if (conditions.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(SELECT + " WHERE " + String.join(" OR ", conditions) + " ORDER BY created_at, id LIMIT :limit")
                .params(params)
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    List<UUID> findIdsWithOtherKey(String activeVersion, UUID after, int limit) {
        return jdbcClient.sql("""
                SELECT id FROM learners
                WHERE key_version <> :activeVersion AND id > :after
                ORDER BY id
                LIMIT :limit
                """)
                .param("activeVersion", activeVersion)
                .param("after", after)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    long countWithOtherKey(String activeVersion) {
        return jdbcClient.sql("SELECT COUNT(*) FROM learners WHERE key_version <> :activeVersion")
                .param("activeVersion", activeVersion)
                .query(Long.class)
                .single();
    }

    long countProfiles() {
        return jdbcClient.sql("SELECT COUNT(*) FROM learners WHERE personal_data_status <> 'ANONYMIZED'")
                .query(Long.class)
                .single();
    }

    boolean updateStatus(UUID id, int expectedVersion, PersonalDataStatus status, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE learners
                SET personal_data_status = :status, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion AND personal_data_status <> 'ANONYMIZED'
                """)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("status", status.name())
                .param("now", now)
                .update() == 1;
    }

    boolean anonymize(UUID id, OffsetDateTime now) {
        return jdbcClient.sql(ANONYMIZE + " id = :id")
                .param("id", id)
                .param("now", now)
                .update() == 1;
    }

    int anonymizeExpired(Collection<UUID> expiredStreamIds, OffsetDateTime now) {
        return jdbcClient.sql(ANONYMIZE + """
                 EXISTS (SELECT 1 FROM learner_enrolments e WHERE e.learner_id = learners.id)
                AND NOT EXISTS (
                    SELECT 1 FROM learner_enrolments e
                    WHERE e.learner_id = learners.id AND e.stream_id NOT IN (:expiredStreamIds)
                )
                """)
                .param("expiredStreamIds", expiredStreamIds)
                .param("now", now)
                .update();
    }

    private Map<String, Object> values(LearnerWrite learner) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", learner.id());
        values.put("keyVersion", learner.keyVersion());
        values.put("fields", json(learner.fields()));
        values.put("emailHmac", learner.emailHmac());
        values.put("phoneHmac", learner.phoneHmac());
        values.put("snilsHmac", learner.snilsHmac());
        values.put("nameHmac", learner.nameHmac());
        values.put("lastNameHmac", learner.lastNameHmac());
        values.put("missingFields", learner.missingFields().stream().map(Enum::name).collect(Collectors.joining(",")));
        return values;
    }

    static Set<LearnerField> missingFields(String missing) {
        Set<LearnerField> missingFields = EnumSet.noneOf(LearnerField.class);
        if (!missing.isEmpty()) {
            Arrays.stream(missing.split(",")).map(LearnerField::valueOf).forEach(missingFields::add);
        }
        return missingFields;
    }

    private LearnerRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        String fields = resultSet.getString("fields");
        return new LearnerRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("key_version"),
                fields == null ? null : fields(fields),
                missingFields(resultSet.getString("missing_fields")),
                PersonalDataStatus.valueOf(resultSet.getString("personal_data_status")),
                resultSet.getInt("version"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private String json(Map<LearnerField, String> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Learner fields cannot be stored", exception);
        }
    }

    private Map<LearnerField, String> fields(String json) {
        try {
            return objectMapper.readValue(json, FIELDS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored learner fields cannot be read", exception);
        }
    }

    enum Fingerprint {
        EMAIL("email_hmac"),
        PHONE("phone_hmac"),
        SNILS("snils_hmac"),
        NAME("name_hmac"),
        LAST_NAME("last_name_hmac");

        private final String column;

        Fingerprint(String column) {
            this.column = column;
        }
    }

    record LearnerWrite(
            UUID id,
            String keyVersion,
            Map<LearnerField, String> fields,
            String emailHmac,
            String phoneHmac,
            String snilsHmac,
            String nameHmac,
            String lastNameHmac,
            Set<LearnerField> missingFields
    ) {
    }

    record LearnerRow(
            UUID id,
            String keyVersion,
            Map<LearnerField, String> fields,
            Set<LearnerField> missingFields,
            PersonalDataStatus status,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}
