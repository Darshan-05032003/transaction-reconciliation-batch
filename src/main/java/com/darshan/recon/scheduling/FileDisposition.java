package com.darshan.recon.scheduling;

public enum FileDisposition {
    // Seen for the first time or still changing size: picked up on a later poll.
    PENDING,
    ARCHIVED,
    FAILED,
    // The job could not even be launched (for example the database was unreachable), so
    // nothing ran and the file stays in the inbox for the next poll.
    RETRY_LATER
}
