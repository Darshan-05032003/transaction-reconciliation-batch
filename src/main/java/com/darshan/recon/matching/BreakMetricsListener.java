package com.darshan.recon.matching;

import com.darshan.recon.common.observability.ReconMetrics;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.stereotype.Component;

// Counts breaks per type as each chunk is written. The count is taken after the writer
// returned, so a chunk that later rolls back is still counted once; for a metric that is
// an acceptable imprecision, the authoritative numbers stay in recon_run and recon_break.
@Component
public class BreakMetricsListener implements ItemWriteListener<ReconBreakRow> {

    private final ReconMetrics metrics;

    public BreakMetricsListener(ReconMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void afterWrite(Chunk<? extends ReconBreakRow> items) {
        Map<ReconBreakType, Integer> counts = new EnumMap<>(ReconBreakType.class);
        for (ReconBreakRow row : items) {
            counts.merge(row.breakType(), 1, Integer::sum);
        }
        counts.forEach(metrics::breaksWritten);
    }
}
