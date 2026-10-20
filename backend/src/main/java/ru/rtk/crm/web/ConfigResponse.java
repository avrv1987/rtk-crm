package ru.rtk.crm.web;

/** landing — режим главной без входа: CONTEST или PRODUCT (настройка администратора «Главная страница»). */
public record ConfigResponse(Modules modules, String landing) {
    public record Modules(boolean enrolment, boolean partner, boolean max, boolean mcp, boolean mail) {
    }
}
