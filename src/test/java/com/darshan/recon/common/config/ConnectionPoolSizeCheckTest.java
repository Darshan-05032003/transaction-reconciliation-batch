package com.darshan.recon.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConnectionPoolSizeCheckTest {

    @ParameterizedTest
    @CsvSource({"10, 4", "10, 1", "6, 2", "4, 1", "130, 64"})
    void acceptsAPoolThatCanServeEveryPartitionAtOnce(int poolSize, int gridSize) {
        assertThatCode(() -> ConnectionPoolSizeCheck.verify(poolSize, gridSize)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"10, 5", "3, 1", "9, 4"})
    void refusesAPoolThatIsTooSmallForTheGridSize(int poolSize, int gridSize) {
        assertThatThrownBy(() -> ConnectionPoolSizeCheck.verify(poolSize, gridSize))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("grid size of " + gridSize)
                .hasMessageContaining("at least " + ConnectionPoolSizeCheck.requiredPoolSize(gridSize))
                .hasMessageContaining("DB_POOL_SIZE");
    }
}
