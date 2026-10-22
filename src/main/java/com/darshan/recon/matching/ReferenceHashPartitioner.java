package com.darshan.recon.matching;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;

// Each partition takes the references whose hash falls on its index. Both sides of a pair
// share the normalised reference, so they always land in the same partition, and a file
// with a single account still splits evenly.
public class ReferenceHashPartitioner implements Partitioner {

    static final String PARTITION_PREFIX = "partition";

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        if (gridSize < 1) {
            throw new IllegalArgumentException("gridSize must be at least 1: " + gridSize);
        }
        Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        for (int index = 0; index < gridSize; index++) {
            ExecutionContext context = new ExecutionContext();
            context.putInt(ReconcileJobKeys.PARTITION_INDEX, index);
            context.putInt(ReconcileJobKeys.PARTITION_COUNT, gridSize);
            partitions.put(PARTITION_PREFIX + index, context);
        }
        return partitions;
    }
}
