package ru.rtk.crm.privacy;

import java.util.List;

import ru.rtk.crm.enrolment.LearnerPrivacyService.SubjectLearner;

public record SubjectSearchResult(
        List<SubjectContact> contacts,
        List<SubjectProfile> profiles,
        List<SubjectMention> mentions,
        List<SubjectAttachment> attachments,
        List<SubjectSourceRecord> sourceRecords,
        List<SubjectLearner> learners,
        List<SubjectPersonRecord> peopleRecords,
        boolean truncated
) {
    SubjectSearchResult withMentions(List<SubjectMention> masked) {
        return new SubjectSearchResult(contacts, profiles, masked, attachments, sourceRecords, learners, peopleRecords, truncated);
    }

    SubjectSearchResult withLearners(List<SubjectLearner> found) {
        return new SubjectSearchResult(contacts, profiles, mentions, attachments, sourceRecords, found, peopleRecords, truncated);
    }
}
