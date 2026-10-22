package com.darshan.recon.report;

import java.nio.file.Path;
import java.time.LocalDate;

public final class ReportFileNames {

    private ReportFileNames() {
    }

    public static Path breakReport(Path reportsFolder, LocalDate statementDate, long reconRunId) {
        return reportsFolder.resolve("recon_%s_%d.csv".formatted(statementDate, reconRunId));
    }

    public static Path runSummary(Path reportsFolder, LocalDate statementDate, long reconRunId) {
        return reportsFolder.resolve("recon_%s_%d_summary.csv".formatted(statementDate, reconRunId));
    }
}
