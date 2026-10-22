package com.darshan.recon.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.matching.ReconBreakType;
import com.darshan.recon.matching.ReconRunStatus;
import com.darshan.recon.resolution.BreakStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RunSummaryTest {

    @ParameterizedTest
    @CsvSource({
            "930, 73, 92.72",
            "1, 4, 20.00",
            "2, 1, 66.67",
            "0, 5, 0.00",
            "5, 0, 100.00",
            "0, 0, 100.00"})
    void matchRateIsAPercentageWithTwoDecimals(int matched, int breaks, String expected) {
        BigDecimal rate = RunSummary.matchRate(matched, breaks);

        assertThat(rate).isEqualByComparingTo(expected);
        assertThat(rate.scale()).isEqualTo(2);
    }

    @Test
    void summaryFileListsTotalsAndOneRowPerBreakType() {
        Map<ReconBreakType, Integer> byType = new EnumMap<>(ReconBreakType.class);
        for (ReconBreakType type : ReconBreakType.values()) {
            byType.put(type, 0);
        }
        byType.put(ReconBreakType.AMOUNT_MISMATCH, 3);
        byType.put(ReconBreakType.MISSING_IN_LEDGER, 1);
        OffsetDateTime started = OffsetDateTime.of(2026, 10, 2, 1, 0, 0, 0, ZoneOffset.UTC);
        RunSummary summary = new RunSummary(12, 7, "statement_BANK01_2026-10-01.csv", "000123456789",
                LocalDate.of(2026, 10, 1), ReconRunStatus.COMPLETED, started, started.plusSeconds(3),
                96, 4, RunSummary.matchRate(96, 4), byType, new EnumMap<>(BreakStatus.class));

        assertThat(WriteRunSummaryTasklet.lines(summary)).containsExactly(
                "metric,value",
                "recon_run_id,12",
                "statement_file_id,7",
                "file_name,statement_BANK01_2026-10-01.csv",
                "account_number,000123456789",
                "statement_date,2026-10-01",
                "status,COMPLETED",
                "matched_count,96",
                "break_count,4",
                "match_rate_percent,96.00",
                "breaks_amount_mismatch,3",
                "breaks_value_date_mismatch,0",
                "breaks_missing_in_bank,0",
                "breaks_missing_in_ledger,1",
                "breaks_duplicate_in_statement,0");
    }

    @Test
    void reportFileNamesCarryTheStatementDateAndRunId() {
        assertThat(ReportFileNames.breakReport(java.nio.file.Path.of("data/reports"), LocalDate.of(2026, 10, 1), 12))
                .isEqualTo(java.nio.file.Path.of("data/reports/recon_2026-10-01_12.csv"));
        assertThat(ReportFileNames.runSummary(java.nio.file.Path.of("data/reports"), LocalDate.of(2026, 10, 1), 12))
                .isEqualTo(java.nio.file.Path.of("data/reports/recon_2026-10-01_12_summary.csv"));
    }
}
