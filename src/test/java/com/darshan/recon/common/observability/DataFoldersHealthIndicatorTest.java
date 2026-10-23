package com.darshan.recon.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.common.config.ReconProperties.Folders;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class DataFoldersHealthIndicatorTest {

    @TempDir
    Path data;

    @Test
    void foldersThatDoNotExistYetAreHealthyWhenTheyCanBeCreated() {
        Health health = indicator(data.resolve("inbox"), data.resolve("reports")).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("inbox", data.resolve("inbox") + " (created on first use)")
                .containsEntry("reports", data.resolve("reports") + " (created on first use)")
                .hasEntrySatisfying("usableBytes", bytes -> assertThat((Long) bytes).isPositive());
    }

    @Test
    void existingWritableFoldersAreReportedByPath() throws IOException {
        Path inbox = Files.createDirectories(data.resolve("inbox"));

        Health health = indicator(inbox, data.resolve("reports")).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("inbox", inbox.toString());
    }

    @Test
    void aFolderConfiguredOnTopOfARegularFileIsDown() throws IOException {
        Path file = Files.writeString(data.resolve("not-a-folder"), "x");

        Health health = indicator(file.resolve("inbox"), data.resolve("reports")).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("inbox", file + " is not a directory");
    }

    @Test
    void anUnwritableFolderIsDown() throws IOException {
        Path inbox = Files.createDirectories(data.resolve("inbox"));
        Files.setPosixFilePermissions(inbox, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        assumeTrue(!Files.isWritable(inbox), "root can write anywhere, so this check cannot run as root");
        try {
            Health health = indicator(inbox, data.resolve("reports")).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("inbox", inbox + " is not writable");
        } finally {
            Files.setPosixFilePermissions(inbox, EnumSet.allOf(PosixFilePermission.class));
        }
    }

    private DataFoldersHealthIndicator indicator(Path inbox, Path reports) {
        Folders folders = new Folders(inbox, data.resolve("archive"), data.resolve("failed"), data.resolve("error"), reports);
        return new DataFoldersHealthIndicator(new ReconProperties(folders, 50, 2, 10, new BigDecimal("0.00"), 3, "-",
                new BigDecimal("1000.00")));
    }
}
