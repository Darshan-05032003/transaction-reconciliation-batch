package com.darshan.recon.common.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

// Every running matching partition holds two connections: the cursor reader's own one and
// the chunk transaction's. A pool that cannot serve every partition at once would not fail at
// boot but at midnight, with connection timeouts in the middle of the matching step.
@Component
public class ConnectionPoolSizeCheck implements InitializingBean {

    static final int CONNECTIONS_PER_PARTITION = 2;
    static final int CONNECTIONS_OUTSIDE_PARTITIONS = 2;

    private final DataSource dataSource;
    private final ReconProperties properties;

    public ConnectionPoolSizeCheck(DataSource dataSource, ReconProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (dataSource instanceof HikariDataSource hikari) {
            verify(hikari.getMaximumPoolSize(), properties.gridSize());
        }
    }

    static int requiredPoolSize(int gridSize) {
        return CONNECTIONS_PER_PARTITION * gridSize + CONNECTIONS_OUTSIDE_PARTITIONS;
    }

    static void verify(int maximumPoolSize, int gridSize) {
        int required = requiredPoolSize(gridSize);
        if (maximumPoolSize < required) {
            throw new IllegalStateException(
                    "The connection pool allows %d connections but a grid size of %d needs at least %d (%d per partition plus %d); raise DB_POOL_SIZE or lower RECON_GRID_SIZE"
                            .formatted(maximumPoolSize, gridSize, required, CONNECTIONS_PER_PARTITION, CONNECTIONS_OUTSIDE_PARTITIONS));
        }
    }
}
