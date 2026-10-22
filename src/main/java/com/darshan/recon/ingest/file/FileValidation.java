package com.darshan.recon.ingest.file;

public sealed interface FileValidation {

    record Accepted(StatementHeader header, StatementTrailer trailer) implements FileValidation {
    }

    record Rejected(String reason) implements FileValidation {
    }
}
