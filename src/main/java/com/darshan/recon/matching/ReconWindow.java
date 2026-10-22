package com.darshan.recon.matching;

import java.time.LocalDate;

// The ledger transactions a statement is expected to cover: the statement date plus or
// minus the date tolerance, because banks apply value dates a few days either side.
public record ReconWindow(LocalDate from, LocalDate to) {

    public ReconWindow {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("window ends before it starts: " + from + ".." + to);
        }
    }

    public static ReconWindow around(LocalDate statementDate, int toleranceDays) {
        return new ReconWindow(statementDate.minusDays(toleranceDays), statementDate.plusDays(toleranceDays));
    }

    public static ReconWindow parse(String from, String to) {
        return new ReconWindow(LocalDate.parse(from), LocalDate.parse(to));
    }
}
