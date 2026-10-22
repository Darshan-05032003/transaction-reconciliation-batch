package com.darshan.recon.ingest;

import java.time.LocalDate;

public record StatementFileRecord(long id, String fileName, String accountNumber, LocalDate statementDate,
                                  StatementFileStatus status) {
}
