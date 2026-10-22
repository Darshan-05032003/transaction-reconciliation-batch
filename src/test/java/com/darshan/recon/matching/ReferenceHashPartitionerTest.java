package com.darshan.recon.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;

class ReferenceHashPartitionerTest {

    private final ReferenceHashPartitioner partitioner = new ReferenceHashPartitioner();

    @Test
    void createsOneContextPerPartitionWithItsIndexAndTheTotal() {
        Map<String, ExecutionContext> partitions = partitioner.partition(4);

        assertThat(partitions.keySet()).containsExactly("partition0", "partition1", "partition2", "partition3");
        partitions.forEach((name, context) -> {
            assertThat(context.getInt(ReconcileJobKeys.PARTITION_COUNT)).isEqualTo(4);
            assertThat(name).isEqualTo("partition" + context.getInt(ReconcileJobKeys.PARTITION_INDEX));
        });
    }

    @Test
    void singlePartitionCoversEverything() {
        Map<String, ExecutionContext> partitions = partitioner.partition(1);

        assertThat(partitions).hasSize(1);
        assertThat(partitions.get("partition0").getInt(ReconcileJobKeys.PARTITION_INDEX)).isZero();
        assertThat(partitions.get("partition0").getInt(ReconcileJobKeys.PARTITION_COUNT)).isEqualTo(1);
    }

    @Test
    void rejectsAGridSizeBelowOne() {
        assertThatThrownBy(() -> partitioner.partition(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
