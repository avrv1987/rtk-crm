package ru.rtk.crm.privacy;

import java.util.List;

public record SubjectSearchResult(
        List<SubjectContact> contacts,
        List<SubjectProfile> profiles,
        List<SubjectMention> mentions,
        List<SubjectAttachment> attachments,
        List<SubjectSourceRecord> sourceRecords,
        boolean truncated
) {
}
