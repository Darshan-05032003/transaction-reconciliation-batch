package com.darshan.recon.testdata;

import java.math.BigDecimal;
import java.time.LocalDate;

public record GenerationManifest(
        String fileName,
        LocalDate statementDate,
        long seed,
        String bankCode,
        String accountNumber,
        int ledgerRows,
        int declaredCount,
        BigDecimal declaredTotal,
        int expectedLoadedLines,
        int expectedSkippedLines,
        int expectedMatched,
        InjectedBreaks injected,
        ExpectedBreaks expectedBreaks) {

    public record InjectedBreaks(
            int amountMismatchPaise,
            int amountMismatchRupees,
            int valueDateShift,
            int missingInBank,
            int extraInBank,
            int duplicateReference,
            int malformedBadAmount,
            int malformedWrongColumnCount,
            int malformedBadDate) {

        public int malformedTotal() {
            return malformedBadAmount + malformedWrongColumnCount + malformedBadDate;
        }
    }

    public record ExpectedBreaks(
            int amountMismatch,
            int valueDateMismatch,
            int missingInBank,
            int missingInLedger,
            int duplicateInStatement) {
    }
}
