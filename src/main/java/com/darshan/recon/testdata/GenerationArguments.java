package com.darshan.recon.testdata;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.ApplicationArguments;

final class GenerationArguments {

    static final String DATE = "date";
    static final String ROWS = "rows";
    static final String SEED = "seed";
    static final int DEFAULT_ROWS = 1000;
    static final long DEFAULT_SEED = 42L;

    private GenerationArguments() {
    }

    static GenerationRequest parse(ApplicationArguments args, LocalDate defaultDate) {
        LocalDate date = singleValue(args, DATE).map(GenerationArguments::parseDate).orElse(defaultDate);
        int rows = singleValue(args, ROWS).map(value -> parseInt(ROWS, value)).orElse(DEFAULT_ROWS);
        long seed = singleValue(args, SEED).map(value -> parseLong(SEED, value)).orElse(DEFAULT_SEED);
        return new GenerationRequest(date, rows, seed);
    }

    private static Optional<String> singleValue(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null) {
            return Optional.empty();
        }
        if (values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("--" + name + " must be given exactly once with a value");
        }
        return Optional.of(values.getFirst());
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("--" + DATE + " must be an ISO date such as 2026-10-01: " + value);
        }
    }

    private static int parseInt(String name, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be an integer: " + value);
        }
    }

    private static long parseLong(String name, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be an integer: " + value);
        }
    }
}
