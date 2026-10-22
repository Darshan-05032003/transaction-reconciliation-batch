package com.darshan.recon.matching.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StatementSide(long id, int lineNumber, String reference, BigDecimal amount, LocalDate valueDate,
                            String description) {
}
