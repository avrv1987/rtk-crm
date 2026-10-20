package ru.rtk.crm.settings;

import java.util.Optional;

import org.springframework.context.annotation.Primary;

// Настройки без правок администратора для тестов со своей схемой H2 без таблицы system_settings: действуют значения
// конфигурации, переданные вызывающим кодом, или запасные значения перечня. Правки на экране проверяют
// SystemSettingsHttpTest и тесты отдельных правил на настоящих миграциях.
@Primary
public class TestSystemSettings extends SystemSettings {
    public TestSystemSettings() {
        super(null, null);
    }

    @Override
    public Optional<Integer> saved(SystemSetting setting) {
        return Optional.empty();
    }
}
