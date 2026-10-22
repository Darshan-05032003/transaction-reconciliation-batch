package com.darshan.recon.matching;

// Shared switch for the fault-injecting processor: armed by a test for one partition and
// one position, disarmed before the restart.
final class MatchingFault {

    private volatile int partitionIndex = -1;
    private volatile int failAfterItems;

    void arm(int partitionIndex, int failAfterItems) {
        this.partitionIndex = partitionIndex;
        this.failAfterItems = failAfterItems;
    }

    void disarm() {
        partitionIndex = -1;
    }

    boolean shouldFail(int partition, int itemsProcessed) {
        return partition == partitionIndex && itemsProcessed > failAfterItems;
    }
}
