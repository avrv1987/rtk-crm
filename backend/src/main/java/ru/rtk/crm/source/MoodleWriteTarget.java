package ru.rtk.crm.source;

import java.time.Duration;

import org.springframework.util.unit.DataSize;

public record MoodleWriteTarget(
        String baseUrl,
        String token,
        int studentRoleId,
        Duration connectTimeout,
        Duration readTimeout,
        DataSize maxResponseSize
) {
    @Override
    public String toString() {
        return "MoodleWriteTarget[baseUrl=" + baseUrl + ", token=masked]";
    }
}
