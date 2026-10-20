package ru.rtk.crm.settings;

import java.time.Duration;
import java.time.Period;
import java.util.Optional;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// Действующее значение настройки: сохранённое администратором, иначе — из конфигурации. Читается из базы при каждом
// обращении, без кэша: правка на экране действует со следующего расчёта, без перезапуска и на всех экземплярах.
// Вызывающий код читает значение один раз на расчёт, а не на строку.
@Component
public class SystemSettings {
    private final JdbcClient jdbcClient;
    private final Environment environment;

    public SystemSettings(JdbcClient jdbcClient, Environment environment) {
        this.jdbcClient = jdbcClient;
        this.environment = environment;
    }

    public Optional<Integer> saved(SystemSetting setting) {
        return jdbcClient.sql("SELECT setting_value FROM system_settings WHERE setting_key = :key")
                .param("key", setting.name())
                .query(Integer.class)
                .optional();
    }

    /** Значение конфигурации сервера в единице настройки. */
    public int configured(SystemSetting setting) {
        if (environment == null) {
            return setting.fallback();
        }
        Binder binder = Binder.get(environment);
        String property = setting.property();
        if (setting.plainNumber()) {
            return binder.bind(property, Integer.class).orElse(setting.fallback());
        }
        return switch (setting.unit()) {
            case DAYS -> binder.bind(property, Duration.class).map(value -> (int) value.toDays()).orElse(setting.fallback());
            case HOURS -> binder.bind(property, Duration.class).map(value -> (int) value.toHours()).orElse(setting.fallback());
            case MONTHS -> binder.bind(property, Period.class).map(value -> (int) value.toTotalMonths()).orElse(setting.fallback());
            case PERCENT -> binder.bind(property, Integer.class).orElse(setting.fallback());
            case FLAG, LANDING -> binder.bind(property, Boolean.class).map(value -> value ? 1 : 0).orElse(setting.fallback());
        };
    }

    public int value(SystemSetting setting) {
        return saved(setting).orElseGet(() -> configured(setting));
    }

    public int value(SystemSetting setting, int configured) {
        return saved(setting).orElse(configured);
    }

    public Duration days(SystemSetting setting, Duration configured) {
        return saved(setting).map(Duration::ofDays).orElse(configured);
    }

    public Duration hours(SystemSetting setting, Duration configured) {
        return saved(setting).map(Duration::ofHours).orElse(configured);
    }

    public Period months(SystemSetting setting, Period configured) {
        return saved(setting).map(months -> Period.ofMonths(months).normalized()).orElse(configured);
    }

    public boolean enabled(SystemSetting setting) {
        return value(setting) != 0;
    }

    // Режим главной читает публичный /api/config при каждом открытии CRM: сбой чтения настройки не должен ронять
    // ответ с модулями, без которого интерфейс не стартует, — тогда действует конфигурация сервера.
    public String landing() {
        int value;
        try {
            value = value(SystemSetting.LANDING_PAGE);
        } catch (DataAccessException exception) {
            value = configured(SystemSetting.LANDING_PAGE);
        }
        return value != 0 ? "PRODUCT" : "CONTEST";
    }
}
