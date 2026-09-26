package ru.rtk.crm.attachment;

import java.io.InputStream;

public interface AttachmentScanner {
    AttachmentScanOutcome scan(InputStream input, long sizeBytes);
}
