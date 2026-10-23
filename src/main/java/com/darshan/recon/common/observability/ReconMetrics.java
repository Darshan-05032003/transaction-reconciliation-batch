package com.darshan.recon.common.observability;

import com.darshan.recon.matching.ReconBreakType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

// The business numbers an operations dashboard wants next to Spring Batch's own job and
// step timers. Every meter is registered at start-up so that a scrape sees zeros instead
// of nothing until the first break is written.
@Component
public class ReconMetrics {

    public static final String BREAKS = "recon.breaks";
    public static final String MATCH_RATE = "recon.match.rate";
    public static final String FILES_REJECTED = "files.rejected";
    public static final String TYPE_TAG = "type";

    private final Map<ReconBreakType, Counter> breaksByType = new EnumMap<>(ReconBreakType.class);
    private final Counter filesRejected;
    private final AtomicReference<BigDecimal> lastMatchRatePercent = new AtomicReference<>();

    public ReconMetrics(MeterRegistry registry) {
        for (ReconBreakType type : ReconBreakType.values()) {
            breaksByType.put(type, Counter.builder(BREAKS)
                    .tag(TYPE_TAG, type.name())
                    .description("Reconciliation breaks written, by break type")
                    .register(registry));
        }
        filesRejected = Counter.builder(FILES_REJECTED)
                .description("Statement files rejected because their control totals did not match")
                .register(registry);
        Gauge.builder(MATCH_RATE, lastMatchRatePercent, ReconMetrics::percentOrNaN)
                .description("Match rate of the last completed reconciliation run, in percent")
                .baseUnit("percent")
                .register(registry);
    }

    public void breaksWritten(ReconBreakType type, int count) {
        breaksByType.get(type).increment(count);
    }

    public void fileRejected() {
        filesRejected.increment();
    }

    public void runCompleted(BigDecimal matchRatePercent) {
        lastMatchRatePercent.set(matchRatePercent);
    }

    // NaN is Micrometer's "no value yet"; a gauge of 0 would read as a run that matched nothing.
    private static double percentOrNaN(AtomicReference<BigDecimal> value) {
        BigDecimal percent = value.get();
        return percent == null ? Double.NaN : percent.doubleValue();
    }
}
