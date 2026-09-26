package ru.rtk.crm.report;

import org.springframework.http.HttpStatus;

public class ReportException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ReportException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ReportException jobNotFound() {
        return new ReportException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Задание отчёта не найдено");
    }

    public static ReportException accessChanged() {
        return new ReportException(
                HttpStatus.GONE,
                "REPORT_ACCESS_CHANGED",
                "Права доступа изменились после заказа отчёта; сформируйте отчёт заново"
        );
    }

    public static ReportException notReady() {
        return new ReportException(HttpStatus.CONFLICT, "REPORT_NOT_READY", "Отчёт ещё не построен или построение завершилось ошибкой");
    }

    public static ReportException resultUnavailable() {
        return new ReportException(
                HttpStatus.GONE,
                "REPORT_RESULT_UNAVAILABLE",
                "Файл отчёта больше не хранится на сервере; сформируйте отчёт заново"
        );
    }

    public static ReportException capacityExceeded() {
        return new ReportException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "REPORT_CAPACITY_EXCEEDED",
                "Все слоты построения отчётов и очередь заняты; повторите запрос позже"
        );
    }

    public static ReportException rowLimit(int maxRows) {
        return new ReportException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "REPORT_ROW_LIMIT",
                "Отчёт содержит больше " + maxRows + " строк; сузьте период или фильтры"
        );
    }

    public static ReportException xlsRowLimit(int maxRows) {
        return new ReportException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "REPORT_XLS_ROW_LIMIT",
                "Формат XLS вмещает не более " + maxRows + " строк данных на листе; выберите XLSX или сузьте фильтры"
        );
    }

    public static ReportException pdfRowLimit(int maxRows) {
        return new ReportException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "REPORT_PDF_ROW_LIMIT",
                "PDF строится не более чем для " + maxRows + " строк; выберите XLSX или сузьте фильтры"
        );
    }

    public static ReportException chartLimit(int maxBars) {
        return new ReportException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "REPORT_CHART_LIMIT",
                "Диаграмма строится не более чем для " + maxBars + " столбцов; сузьте период или фильтры либо выберите другую группировку"
        );
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
