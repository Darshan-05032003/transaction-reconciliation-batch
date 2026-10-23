package com.darshan.recon.common.observability;

import com.darshan.recon.common.config.ReconProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

// The jobs read from and move files between the configured folders, so an unwritable
// folder (a wrong mount, a permission change) is reported here before the first file lands.
// A folder that does not exist yet is created on first use and counts as healthy as long
// as it can be created, which is what a fresh deployment looks like.
@Component
public class DataFoldersHealthIndicator extends AbstractHealthIndicator {

    private final Map<String, Path> folders;

    public DataFoldersHealthIndicator(ReconProperties properties) {
        ReconProperties.Folders configured = properties.folders();
        Map<String, Path> named = new LinkedHashMap<>();
        named.put("inbox", configured.inbox());
        named.put("archive", configured.archive());
        named.put("failed", configured.failed());
        named.put("error", configured.error());
        named.put("reports", configured.reports());
        this.folders = Map.copyOf(named);
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws IOException {
        boolean healthy = true;
        for (Map.Entry<String, Path> folder : folders.entrySet()) {
            Path path = folder.getValue().toAbsolutePath().normalize();
            String problem = problem(path);
            builder.withDetail(folder.getKey(), problem == null ? describe(path) : problem);
            healthy &= problem == null;
        }
        builder.withDetail("usableBytes", Files.getFileStore(nearestExisting(folders.get("inbox"))).getUsableSpace());
        if (healthy) {
            builder.up();
        } else {
            builder.down();
        }
    }

    private static String problem(Path folder) {
        Path existing = nearestExisting(folder);
        if (!Files.isDirectory(existing)) {
            return existing + " is not a directory";
        }
        if (!Files.isWritable(existing)) {
            return existing + " is not writable";
        }
        return null;
    }

    private static String describe(Path folder) {
        return Files.exists(folder) ? folder.toString() : folder + " (created on first use)";
    }

    private static Path nearestExisting(Path folder) {
        Path candidate = folder.toAbsolutePath().normalize();
        while (!Files.exists(candidate)) {
            candidate = candidate.getParent();
        }
        return candidate;
    }
}
