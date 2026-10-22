package com.darshan.recon.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.matching.engine.BreakSeverity;
import com.darshan.recon.matching.engine.CandidatePair;
import com.darshan.recon.matching.engine.LedgerSide;
import com.darshan.recon.matching.engine.MatchingEngine;
import com.darshan.recon.matching.engine.StatementSide;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MatchingItemProcessorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final long RUN_ID = 42L;

    private final MatchingItemProcessor processor =
            new MatchingItemProcessor(MatchingEngine.standard(new BigDecimal("0.00"), 3), RUN_ID);

    @Test
    void matchedPairsAreFilteredOutInsteadOfWritten() {
        assertThat(processor.process(CandidatePair.paired(ledger("TXN-1", "100.00", DATE), statement(5, "txn-1", "100.0", DATE))))
                .isNull();
    }

    @Test
    void amountMismatchCarriesBothAmountsAndTheSignedDifference() {
        ReconBreakRow row = processor.process(
                CandidatePair.paired(ledger("TXN-1", "100.00", DATE), statement(5, "TXN-1", "98.50", DATE)));

        assertThat(row).isEqualTo(new ReconBreakRow(RUN_ID, ReconBreakType.AMOUNT_MISMATCH, BreakSeverity.ERROR, 1L, 5L,
                new BigDecimal("100.00"), new BigDecimal("98.50"),
                "amount differs by -1.50 (ledger 100.00, bank 98.50, tolerance 0.00)"));
    }

    @Test
    void valueDateMismatchStatesWhetherItIsWithinTheTolerance() {
        ReconBreakRow warning = processor.process(
                CandidatePair.paired(ledger("TXN-1", "100.00", DATE), statement(5, "TXN-1", "100.00", DATE.plusDays(2))));
        ReconBreakRow error = processor.process(
                CandidatePair.paired(ledger("TXN-1", "100.00", DATE), statement(5, "TXN-1", "100.00", DATE.minusDays(9))));

        assertThat(warning.breakType()).isEqualTo(ReconBreakType.VALUE_DATE_MISMATCH);
        assertThat(warning.severity()).isEqualTo(BreakSeverity.WARNING);
        assertThat(error.severity()).isEqualTo(BreakSeverity.ERROR);
        assertThat(warning.details()).isEqualTo(
                "value date differs by 2 day(s) (ledger 2026-10-01, bank 2026-10-03), within tolerance of 3 day(s): warning");
        assertThat(error.details()).isEqualTo(
                "value date differs by -9 day(s) (ledger 2026-10-01, bank 2026-09-22), outside tolerance of 3 day(s): error");
        assertThat(warning.ledgerAmount()).isEqualByComparingTo("100.00");
        assertThat(warning.bankAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void missingSidesLeaveTheAbsentIdAndAmountNull() {
        ReconBreakRow missingInBank = processor.process(CandidatePair.ledgerOnly(ledger("TXN-7", "250.00", DATE)));
        ReconBreakRow missingInLedger = processor.process(CandidatePair.statementOnly(statement(9, "TXN-8", "-30.00", DATE)));

        assertThat(missingInBank).isEqualTo(new ReconBreakRow(RUN_ID, ReconBreakType.MISSING_IN_BANK, BreakSeverity.ERROR, 1L, null,
                new BigDecimal("250.00"), null, "ledger transaction TXN-7 with value date 2026-10-01 has no statement line"));
        assertThat(missingInLedger).isEqualTo(new ReconBreakRow(RUN_ID, ReconBreakType.MISSING_IN_LEDGER, BreakSeverity.ERROR, null, 9L,
                null, new BigDecimal("-30.00"), "statement line 9 (TXN-8) has no ledger transaction"));
    }

    @Test
    void duplicateReportsEveryLineAndWhetherALedgerTransactionWasLeftUnmatched() {
        ReconBreakRow withLedger = processor.process(new CandidatePair(
                ledger("TXN-1", "100.00", DATE), statement(5, "TXN-1", "100.00", DATE), List.of(5, 61)));
        ReconBreakRow withoutLedger = processor.process(new CandidatePair(
                null, statement(5, "TXN-2", "100.00", DATE), List.of(5, 6, 7)));

        assertThat(withLedger).isEqualTo(new ReconBreakRow(RUN_ID, ReconBreakType.DUPLICATE_IN_STATEMENT, BreakSeverity.ERROR, 1L, 5L,
                new BigDecimal("100.00"), new BigDecimal("100.00"),
                "reference TXN-1 appears 2 times in the statement (lines 5, 61); ledger transaction TXN-1 left unmatched"));
        assertThat(withoutLedger).isEqualTo(new ReconBreakRow(RUN_ID, ReconBreakType.DUPLICATE_IN_STATEMENT, BreakSeverity.ERROR, null, 5L,
                null, new BigDecimal("100.00"),
                "reference TXN-2 appears 3 times in the statement (lines 5, 6, 7); no ledger transaction"));
    }

    @Test
    void detailsAreTruncatedToTheColumnWidth() {
        List<Integer> manyLines = IntStream.rangeClosed(1, 200).boxed().toList();

        ReconBreakRow row = processor.process(new CandidatePair(null, statement(1, "TXN-2", "1.00", DATE), manyLines));

        assertThat(row.details()).hasSize(MatchingItemProcessor.MAX_DETAILS_LENGTH)
                .startsWith("reference TXN-2 appears 200 times");
    }

    private static LedgerSide ledger(String reference, String amount, LocalDate valueDate) {
        return new LedgerSide(1L, reference, new BigDecimal(amount), valueDate, null);
    }

    private static StatementSide statement(long id, String reference, String amount, LocalDate valueDate) {
        return new StatementSide(id, (int) id, reference, new BigDecimal(amount), valueDate, null);
    }
}
