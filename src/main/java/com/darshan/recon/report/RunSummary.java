package com.darshan.recon.report;

import com.darshan.recon.matching.ReconBreakType;
import com.darshan.recon.matching.ReconRunStatus;
import com.darshan.recon.resolution.BreakStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

public record RunSummary(
        long reconRunId,
        long statementFileId,
        String fileName,
        String accountNumber,
        LocalDate statementDate,
        ReconRunStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        int matchedCount,
        int breakCount,
        BigDecimal matchRatePercent,
        Map<ReconBreakType, Integer> breaksByType,
        Map<BreakStatus, Integer> breaksByStatus) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    // A run with nothing to compare left nothing unmatched, so it reports a full match.
    public static BigDecimal matchRate(int matched, int breaks) {
        int total = matched + breaks;
        if (total == 0) {
            return HUNDRED.setScale(2, RoundingMode.HALF_EVEN);
        }
        return BigDecimal.valueOf(matched).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_EVEN);
    }
}
