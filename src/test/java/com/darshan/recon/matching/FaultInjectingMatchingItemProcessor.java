package com.darshan.recon.matching;

import com.darshan.recon.matching.engine.CandidatePair;
import com.darshan.recon.matching.engine.MatchingEngine;

// One instance per partition execution (step scope), so the item count is per partition.
final class FaultInjectingMatchingItemProcessor extends MatchingItemProcessor {

    private final int partitionIndex;
    private final MatchingFault fault;
    private int itemsProcessed;

    FaultInjectingMatchingItemProcessor(MatchingEngine engine, long reconRunId, int partitionIndex, MatchingFault fault) {
        super(engine, reconRunId);
        this.partitionIndex = partitionIndex;
        this.fault = fault;
    }

    @Override
    public ReconBreakRow process(CandidatePair pair) {
        itemsProcessed++;
        if (fault.shouldFail(partitionIndex, itemsProcessed)) {
            throw new IllegalStateException("injected failure in partition %d at item %d"
                    .formatted(partitionIndex, itemsProcessed));
        }
        return super.process(pair);
    }
}
