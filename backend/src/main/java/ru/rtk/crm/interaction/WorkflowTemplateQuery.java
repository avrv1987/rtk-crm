package ru.rtk.crm.interaction;

record WorkflowTemplateQuery(int page, int size, WorkflowTemplateSort sort) {
    static WorkflowTemplateQuery from(int page, int size, String sort) {
        if (page < 0) {
            throw new InteractionValidationException("page", "Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > 100) {
            throw new InteractionValidationException("size", "Размер страницы должен быть от 1 до 100");
        }
        return new WorkflowTemplateQuery(page, size, WorkflowTemplateSort.from(sort));
    }

    int offset() {
        try {
            return Math.multiplyExact(page, size);
        } catch (ArithmeticException exception) {
            throw new InteractionValidationException("page", "Слишком большой номер страницы");
        }
    }

}
