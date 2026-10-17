package ru.rtk.crm.source;

import java.util.UUID;

interface SourceRecordApplier {
    SourceRecordStatus reapply(UUID recordId, UUID actorProfileId);

    SourceRecordStatus reresolve(UUID recordId, UUID actorProfileId);

    void markFailed(UUID recordId, String error);
}
