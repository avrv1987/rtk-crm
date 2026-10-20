package ru.rtk.crm.interaction;

public enum InteractionEventType {
    CREATED,
    TRANSITIONED,
    COMMENTED,
    STAGES_EDITED,
    PLAN_UPDATED,
    DETAILS_UPDATED,
    STATUS_CHANGED,
    AGREEMENT_UPDATED,
    ATTACHMENT_DELETED,
    STAGE_COMPLETED,
    STAGE_COMPLETION_CLEARED,
    // Ниже — события из соседних таблиц (документы, история статусов договора, циклы). В interaction_events они не
    // пишутся, их добавляет к истории карточки InteractionRelatedEvents: у каждого уже есть своя запись с автором и временем.
    DOCUMENT_ADDED,
    AGREEMENT_STATUS_CHANGED,
    CYCLE_STARTED
}
