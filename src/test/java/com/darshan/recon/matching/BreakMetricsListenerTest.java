package com.darshan.recon.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.common.observability.ReconMetrics;
import com.darshan.recon.matching.engine.BreakSeverity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;

class BreakMetricsListenerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BreakMetricsListener listener = new BreakMetricsListener(new ReconMetrics(registry));

    @Test
    void countsTheWrittenChunkPerBreakType() {
        listener.afterWrite(Chunk.of(
                row(ReconBreakType.AMOUNT_MISMATCH),
                row(ReconBreakType.AMOUNT_MISMATCH),
                row(ReconBreakType.DUPLICATE_IN_STATEMENT)));
        listener.afterWrite(Chunk.of(row(ReconBreakType.AMOUNT_MISMATCH)));

        assertThat(count(ReconBreakType.AMOUNT_MISMATCH)).isEqualTo(3);
        assertThat(count(ReconBreakType.DUPLICATE_IN_STATEMENT)).isEqualTo(1);
        assertThat(count(ReconBreakType.MISSING_IN_BANK)).isZero();
    }

    @Test
    void anEmptyChunkChangesNothing() {
        listener.afterWrite(new Chunk<>());

        for (ReconBreakType type : ReconBreakType.values()) {
            assertThat(count(type)).isZero();
        }
    }

    private double count(ReconBreakType type) {
        return registry.get(ReconMetrics.BREAKS).tag(ReconMetrics.TYPE_TAG, type.name()).counter().count();
    }

    private static ReconBreakRow row(ReconBreakType type) {
        return new ReconBreakRow(1L, type, BreakSeverity.ERROR, 10L, 20L, new BigDecimal("100.00"),
                new BigDecimal("99.00"), "details");
    }
}
