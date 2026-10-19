package ru.rtk.crm.enrolment;

// stopsBatch: Moodle недоступен или токен не принят — остальные строки партии отправлять бессмысленно, они остаются
// «ожидает» и не теряются. Отказ по одной строке (rejected) становится ошибкой этой строки.
class MoodleWriteException extends RuntimeException {
    private final String code;
    private final boolean stopsBatch;

    private MoodleWriteException(String code, String message, boolean stopsBatch) {
        super(message);
        this.code = code;
        this.stopsBatch = stopsBatch;
    }

    static MoodleWriteException unavailable(String message) {
        return new MoodleWriteException(null, message, true);
    }

    static MoodleWriteException access(String message) {
        return new MoodleWriteException(null, message, true);
    }

    static MoodleWriteException invalid(String message) {
        return new MoodleWriteException(null, message, false);
    }

    // Текст Moodle английский и ничего не говорит менеджеру учебного процесса, поэтому в строку партии идёт русское объяснение с кодом
    // в скобках; исходное сообщение не сохраняется — в нём бывают логин и почта.
    static MoodleWriteException rejected(String function, String code) {
        return new MoodleWriteException(code, "Moodle: " + explain(function, code) + " (" + code + ")", false);
    }

    static String explain(String function, String code) {
        return switch (code) {
            case "wsusercannotassign" -> "у сервиса CRM нет права назначать роль студента на курсе, обратитесь к администратору Moodle";
            // Moodle отвечает invalidparameter на повтор почты или логина при создании; CRM создаёт пользователя,
            // только если поиск по почте его не нашёл, значит сервис не видит почту.
            case "invalidparameter" -> "core_user_create_users".equals(function)
                    ? "пользователь с такой почтой или логином уже есть, но CRM его не видит: проверьте у сервиса записи право"
                    + " moodle/site:viewuseridentity"
                    : "данные запроса не приняты, проверьте курс, группу и анкету слушателя";
            case "emailexists", "usernameexists" -> "пользователь с такой почтой или логином уже есть";
            case "invalidrecord", "invaliduser", "invaliduserid", "usernotfound" -> "пользователь не найден";
            // codingerror и незнакомые коды — сбой внутри Moodle (кэш, права на каталоги, модули): оператор его не исправит.
            case "codingerror", "dmlreadexception", "dmlwriteexception" -> "сбой на стороне Moodle, обратитесь к администратору Moodle, "
                    + "после исправления повторите передачу";
            default -> "запрос отклонён, обратитесь к администратору Moodle";
        };
    }

    String code() {
        return code;
    }

    boolean stopsBatch() {
        return stopsBatch;
    }
}
