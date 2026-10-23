package com.darshan.recon.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.matching.ReconBreakType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ReconMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReconMetrics metrics = new ReconMetrics(registry);

    @Test
    void everyBreakTypeHasACounterFromTheStart() {
        for (ReconBreakType type : ReconBreakType.values()) {
            assertThat(registry.get(ReconMetrics.BREAKS).tag(ReconMetrics.TYPE_TAG, type.name()).counter().count())
                    .isZero();
        }
        assertThat(registry.get(ReconMetrics.FILES_REJECTED).counter().count()).isZero();
    }

    @Test
    void breaksAreCountedPerType() {
        metrics.breaksWritten(ReconBreakType.AMOUNT_MISMATCH, 3);
        metrics.breaksWritten(ReconBreakType.AMOUNT_MISMATCH, 2);
        metrics.breaksWritten(ReconBreakType.MISSING_IN_BANK, 1);

        assertThat(count(ReconBreakType.AMOUNT_MISMATCH)).isEqualTo(5);
        assertThat(count(ReconBreakType.MISSING_IN_BANK)).isEqualTo(1);
        assertThat(count(ReconBreakType.MISSING_IN_LEDGER)).isZero();
    }

    @Test
    void matchRateIsUnknownUntilARunCompletesAndThenShowsTheLastRun() {
        assertThat(registry.get(ReconMetrics.MATCH_RATE).gauge().value()).isNaN();

        metrics.runCompleted(new BigDecimal("92.97"));
        metrics.runCompleted(new BigDecimal("100.00"));

        assertThat(registry.get(ReconMetrics.MATCH_RATE).gauge().value()).isEqualTo(100.0);
    }

    @Test
    void rejectedFilesAreCounted() {
        metrics.fileRejected();
        metrics.fileRejected();

        assertThat(registry.get(ReconMetrics.FILES_REJECTED).counter().count()).isEqualTo(2);
    }

    private double count(ReconBreakType type) {
        return registry.get(ReconMetrics.BREAKS).tag(ReconMetrics.TYPE_TAG, type.name()).counter().count();
    }
}
