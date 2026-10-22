package com.darshan.recon.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.recon.common.exception.BusinessRuleViolationException;
import com.darshan.recon.common.exception.ConflictException;
import com.darshan.recon.matching.ReconBreakType;
import com.darshan.recon.matching.engine.BreakSeverity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReconBreakTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 2, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final BigDecimal THRESHOLD = new BigDecimal("1000.00");

    @ParameterizedTest
    @CsvSource({
            "AMOUNT_MISMATCH, 100.00, 98.50, 1.50",
            "AMOUNT_MISMATCH, 450.00, -450.00, 900.00",
            "VALUE_DATE_MISMATCH, 20000.00, 20000.00, 20000.00",
            "MISSING_IN_BANK, -250.00, , 250.00",
            "MISSING_IN_LEDGER, , 15.25, 15.25",
            "DUPLICATE_IN_STATEMENT, 100.00, 100.00, 100.00"})
    void exposureIsTheAmountAtStakeForTheBreakType(ReconBreakType type, String ledger, String bank, String expected) {
        ReconBreak reconBreak = open(type, ledger, bank);

        assertThat(reconBreak.exposure()).isEqualByComparingTo(expected);
        assertThat(reconBreak.exposure().scale()).isEqualTo(2);
    }

    @Test
    void writeOffAtTheThresholdNeedsNoApprovalButAboveItDoes() {
        assertThat(open(ReconBreakType.MISSING_IN_LEDGER, null, "1000.00").needsApproval(THRESHOLD)).isFalse();
        assertThat(open(ReconBreakType.MISSING_IN_LEDGER, null, "1000.01").needsApproval(THRESHOLD)).isTrue();
    }

    @Test
    void resolvingRecordsWhoWhenAndWhy() {
        ReconBreak reconBreak = open(ReconBreakType.AMOUNT_MISMATCH, "100.00", "98.50");

        reconBreak.resolve("ops.anita", "bank charges posted separately", NOW);

        assertThat(reconBreak.getStatus()).isEqualTo(BreakStatus.RESOLVED);
        assertThat(reconBreak.getResolvedBy()).isEqualTo("ops.anita");
        assertThat(reconBreak.getResolvedAt()).isEqualTo(NOW);
        assertThat(reconBreak.getResolutionComment()).isEqualTo("bank charges posted separately");
        assertThat(reconBreak.getRequestedBy()).isNull();
        assertThat(reconBreak.getApprovedBy()).isNull();
    }

    @Test
    void aResolvedBreakCannotBeResolvedOrWrittenOffAgain() {
        ReconBreak reconBreak = open(ReconBreakType.AMOUNT_MISMATCH, "100.00", "98.50");
        reconBreak.resolve("ops.anita", "done", NOW);

        assertThatThrownBy(() -> reconBreak.resolve("ops.ravi", "again", NOW))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("RESOLVED");
        assertThatThrownBy(() -> reconBreak.writeOff("ops.ravi", "again", NOW))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> reconBreak.requestWriteOff("ops.ravi", "again", NOW))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void approvedWriteOffKeepsTheMakerAsResolverAndRecordsTheChecker() {
        ReconBreak reconBreak = open(ReconBreakType.MISSING_IN_BANK, "25000.00", null);

        reconBreak.requestWriteOff("ops.anita", "counterparty confirmed reversal", NOW);
        assertThat(reconBreak.getStatus()).isEqualTo(BreakStatus.PENDING_APPROVAL);
        assertThat(reconBreak.getRequestedBy()).isEqualTo("ops.anita");
        assertThat(reconBreak.getResolvedBy()).isNull();

        reconBreak.approveWriteOff("lead.sunil", NOW.plusHours(1));

        assertThat(reconBreak.getStatus()).isEqualTo(BreakStatus.WRITTEN_OFF);
        assertThat(reconBreak.getResolvedBy()).isEqualTo("ops.anita");
        assertThat(reconBreak.getResolvedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(reconBreak.getApprovedBy()).isEqualTo("lead.sunil");
        assertThat(reconBreak.getApprovedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(reconBreak.getResolutionComment()).isEqualTo("counterparty confirmed reversal");
    }

    @Test
    void theRequesterCannotApproveTheirOwnWriteOff() {
        ReconBreak reconBreak = open(ReconBreakType.MISSING_IN_BANK, "25000.00", null);
        reconBreak.requestWriteOff("ops.anita", "please approve", NOW);

        assertThatThrownBy(() -> reconBreak.approveWriteOff("ops.anita", NOW))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(e -> ((BusinessRuleViolationException) e).errorCode())
                .isEqualTo(ReconBreak.SELF_APPROVAL);
        assertThat(reconBreak.getStatus()).isEqualTo(BreakStatus.PENDING_APPROVAL);
    }

    @Test
    void rejectionReopensTheBreakAndClearsTheRequest() {
        ReconBreak reconBreak = open(ReconBreakType.MISSING_IN_BANK, "25000.00", null);
        reconBreak.requestWriteOff("ops.anita", "please approve", NOW);

        reconBreak.rejectWriteOff();

        assertThat(reconBreak.getStatus()).isEqualTo(BreakStatus.OPEN);
        assertThat(reconBreak.getRequestedBy()).isNull();
        assertThat(reconBreak.getRequestedAt()).isNull();
        assertThat(reconBreak.getResolutionComment()).isNull();
        reconBreak.resolve("ops.anita", "investigated instead", NOW);
        assertThat(reconBreak.getStatus()).isEqualTo(BreakStatus.RESOLVED);
    }

    @Test
    void onlyAPendingWriteOffCanBeApprovedOrRejected() {
        ReconBreak reconBreak = open(ReconBreakType.MISSING_IN_BANK, "25.00", null);

        assertThatThrownBy(() -> reconBreak.approveWriteOff("lead.sunil", NOW))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PENDING_APPROVAL");
        assertThatThrownBy(reconBreak::rejectWriteOff)
                .isInstanceOf(ConflictException.class);
    }

    private static ReconBreak open(ReconBreakType type, String ledgerAmount, String bankAmount) {
        return new ReconBreak(1L, type, BreakSeverity.ERROR,
                ledgerAmount == null ? null : 10L, bankAmount == null ? null : 20L,
                ledgerAmount == null ? null : new BigDecimal(ledgerAmount),
                bankAmount == null ? null : new BigDecimal(bankAmount), "details");
    }
}
