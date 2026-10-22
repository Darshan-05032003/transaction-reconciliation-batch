package com.darshan.recon.ingest.file;

import java.time.LocalDate;

public record StatementHeader(String bankCode, String accountNumber, LocalDate statementDate) {
}
