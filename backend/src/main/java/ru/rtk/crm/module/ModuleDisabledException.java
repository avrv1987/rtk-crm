package ru.rtk.crm.module;

public class ModuleDisabledException extends RuntimeException {
    private ModuleDisabledException(String message) {
        super(message);
    }

    public static ModuleDisabledException enrolment() {
        return new ModuleDisabledException("Раздел «Зачисление» недоступен: модуль «Слушатели» выключен");
    }

    public static ModuleDisabledException partner() {
        return new ModuleDisabledException("Кабинет вуза недоступен: модуль выключен");
    }
}
