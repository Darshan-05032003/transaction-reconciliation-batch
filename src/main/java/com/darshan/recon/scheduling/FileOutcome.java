package com.darshan.recon.scheduling;

import java.nio.file.Path;

public record FileOutcome(Path file, FileDisposition disposition, Path movedTo, String reason) {

    static FileOutcome pending(Path file) {
        return new FileOutcome(file, FileDisposition.PENDING, null, "waiting for the file to stop changing");
    }

    static FileOutcome archived(Path file, Path movedTo, String reason) {
        return new FileOutcome(file, FileDisposition.ARCHIVED, movedTo, reason);
    }

    static FileOutcome failed(Path file, Path movedTo, String reason) {
        return new FileOutcome(file, FileDisposition.FAILED, movedTo, reason);
    }

    static FileOutcome retryLater(Path file, String reason) {
        return new FileOutcome(file, FileDisposition.RETRY_LATER, null, reason);
    }
}
