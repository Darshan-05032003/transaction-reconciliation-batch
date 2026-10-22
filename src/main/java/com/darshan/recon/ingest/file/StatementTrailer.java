package com.darshan.recon.ingest.file;

import java.math.BigDecimal;

public record StatementTrailer(int recordCount, BigDecimal totalAmount) {
}
