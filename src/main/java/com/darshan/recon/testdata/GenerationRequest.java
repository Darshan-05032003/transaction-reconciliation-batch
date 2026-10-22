package com.darshan.recon.testdata;

import java.time.LocalDate;
import java.util.Objects;

public record GenerationRequest(LocalDate date, int rows, long seed) {

    public GenerationRequest {
        Objects.requireNonNull(date, "date is required");
        if (rows < 1) {
            throw new IllegalArgumentException("rows must be at least 1 but was " + rows);
        }
    }
}
