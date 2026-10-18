package ru.rtk.crm.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.Approval;
import ru.rtk.crm.agreement.AgreementModels.ApprovalColor;
import ru.rtk.crm.agreement.AgreementModels.ApprovalSide;
import ru.rtk.crm.agreement.AgreementModels.StatusEntry;

class AgreementApprovalTest {
    private final AgreementApproval approval = new AgreementApproval(42, 21, 7, 60, 90, List.of(7, 8));

    @Test
    void julyAndAugustAreNotCounted() {
        assertThat(approval.countedDays(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"))).isEqualTo(29);
        assertThat(approval.countedDays(LocalDate.parse("2026-06-30"), LocalDate.parse("2026-09-01"))).isEqualTo(1);
        assertThat(approval.countedDays(LocalDate.parse("2026-06-20"), LocalDate.parse("2026-09-10"))).isEqualTo(20);
        assertThat(approval.countedDays(LocalDate.parse("2026-07-10"), LocalDate.parse("2026-08-31"))).isZero();
        assertThat(approval.countedDays(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-01"))).isZero();
        assertThat(approval.countedDays(LocalDate.parse("2026-03-10"), LocalDate.parse("2026-03-01"))).isZero();
        assertThat(new AgreementApproval(42, 21, 7, 60, 90, List.of()).countedDays(LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-09-01"))).isEqualTo(63);
    }

    @Test
    void roundIsTheNumberOfPassesThroughReviewAndSideFollowsTheStatus() {
        List<StatusEntry> twoRounds = List.of(
                entry(AgreementStatus.DRAFT, "2026-02-01"),
                entry(AgreementStatus.REVIEW, "2026-02-02"),
                entry(AgreementStatus.CORRECTION, "2026-02-20"),
                entry(AgreementStatus.REVIEW, "2026-02-25"));
        LocalDate today = LocalDate.parse("2026-03-01");

        Approval second = approval.state(AgreementStatus.REVIEW, at("2026-02-25"), LocalDate.parse("2026-02-02"), twoRounds, today);
        assertThat(second.side()).isEqualTo(ApprovalSide.UNIVERSITY);
        assertThat(second.round()).isEqualTo(2);
        assertThat(second.sideLimitDays()).isEqualTo(21);
        assertThat(second.sideDays()).isEqualTo(4);
        assertThat(second.totalDays()).isEqualTo(27);

        Approval correction = approval.state(AgreementStatus.CORRECTION, at("2026-02-20"), LocalDate.parse("2026-02-02"),
                twoRounds.subList(0, 3), today);
        assertThat(correction.side()).isEqualTo(ApprovalSide.SCHOOL);
        assertThat(correction.round()).isEqualTo(1);
        assertThat(correction.sideLimitDays()).isEqualTo(7);

        Approval signing = approval.state(AgreementStatus.SIGNING, at("2026-02-28"), null, twoRounds, today);
        assertThat(signing.side()).isEqualTo(ApprovalSide.SIGNING);
        assertThat(signing.sideLimitDays()).isNull();
        assertThat(signing.totalDays()).as("без даты отправки — с первого входа в «На согласовании»").isEqualTo(27);

        Approval first = approval.state(AgreementStatus.REVIEW, at("2026-02-02"), null, List.of(), today);
        assertThat(first.round()).isEqualTo(1);
        assertThat(first.sideLimitDays()).isEqualTo(42);

        assertThat(approval.state(AgreementStatus.DRAFT, at("2026-02-02"), null, twoRounds, today)).isNull();
        assertThat(approval.state(AgreementStatus.ACTIVE, at("2026-02-02"), null, twoRounds, today)).isNull();
    }

    @Test
    void colorFollowsTheSideAndTotalThresholds() {
        List<StatusEntry> first = List.of(entry(AgreementStatus.REVIEW, "2026-01-01"));
        LocalDate sent = LocalDate.parse("2026-01-01");

        assertThat(approval.state(AgreementStatus.REVIEW, at("2026-01-01"), sent, first, sent.plusDays(42)).color())
                .isEqualTo(ApprovalColor.NORMAL);
        assertThat(approval.state(AgreementStatus.REVIEW, at("2026-01-01"), sent, first, sent.plusDays(43)).color())
                .as("у вуза дольше 6 недель в первом круге").isEqualTo(ApprovalColor.YELLOW);
        assertThat(approval.state(AgreementStatus.CORRECTION, at("2026-01-10"), sent, first, LocalDate.parse("2026-01-18")).color())
                .as("у школы дольше недели").isEqualTo(ApprovalColor.YELLOW);
        assertThat(approval.state(AgreementStatus.CORRECTION, at("2026-02-25"), sent, first, LocalDate.parse("2026-03-01")).color())
                .as("общий срок 59 дней").isEqualTo(ApprovalColor.NORMAL);
        assertThat(approval.state(AgreementStatus.CORRECTION, at("2026-02-25"), sent, first, LocalDate.parse("2026-03-02")).color())
                .as("общий срок 60 дней").isEqualTo(ApprovalColor.YELLOW);
        assertThat(approval.state(AgreementStatus.SIGNING, at("2026-03-30"), sent, first, LocalDate.parse("2026-04-01")).color())
                .as("общий срок 90 дней").isEqualTo(ApprovalColor.RED);

        LocalDate june = LocalDate.parse("2026-06-15");
        Approval summer = approval.state(AgreementStatus.REVIEW, at("2026-06-15"), june, List.of(entry(AgreementStatus.REVIEW, "2026-06-15")),
                LocalDate.parse("2026-09-15"));
        assertThat(summer.sideDays()).as("июль и август не считаются").isEqualTo(30);
        assertThat(summer.color()).isEqualTo(ApprovalColor.NORMAL);
    }

    @Test
    void signedVersionIgnoresReopeningAfterCompletionAndContractsNeverActive() {
        AgreementModels.AgreementFile draft = file(1, "2026-02-01", true);
        AgreementModels.AgreementFile agreed = file(2, "2026-02-20", true);
        AgreementModels.AgreementFile rejected = file(3, "2026-02-25", false);
        AgreementModels.AgreementFile scan = file(4, "2026-04-01", true);
        List<AgreementModels.AgreementFile> files = List.of(scan, rejected, agreed, draft);
        List<AgreementModels.StatusEvent> history = List.of(
                event(AgreementStatus.COMPLETED, AgreementStatus.ACTIVE, "2026-05-01"),
                event(AgreementStatus.ACTIVE, AgreementStatus.COMPLETED, "2026-04-20"),
                event(AgreementStatus.SIGNING, AgreementStatus.ACTIVE, "2026-03-01"),
                event(null, AgreementStatus.DRAFT, "2026-01-15"));

        assertThat(AgreementRepository.signedFileId(files, history)).isEqualTo(agreed.id());
        assertThat(AgreementRepository.signedFileId(files, history.subList(3, 4))).isNull();
        assertThat(AgreementRepository.signedFileId(files, List.of(event(null, AgreementStatus.ACTIVE, "2026-01-10")))).isNull();
    }

    private static AgreementModels.AgreementFile file(int version, String date, boolean clean) {
        return new AgreementModels.AgreementFile(java.util.UUID.randomUUID(), null, null, version, "v" + version + ".pdf",
                "application/pdf", 1, clean ? "CLEAN" : "REJECTED", clean, null, null, at(date), null, false);
    }

    private static AgreementModels.StatusEvent event(AgreementStatus from, AgreementStatus to, String date) {
        return new AgreementModels.StatusEvent(java.util.UUID.randomUUID(), from, to, null, null, at(date), null);
    }

    private static StatusEntry entry(AgreementStatus status, String date) {
        return new StatusEntry(status, at(date));
    }

    private static OffsetDateTime at(String date) {
        return LocalDate.parse(date).atTime(12, 0).atZone(AgreementService.ZONE).toOffsetDateTime();
    }
}
