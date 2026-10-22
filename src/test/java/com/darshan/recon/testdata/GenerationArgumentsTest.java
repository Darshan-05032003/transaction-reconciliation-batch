package com.darshan.recon.testdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class GenerationArgumentsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @Test
    void parsesAllThreeOptions() {
        GenerationRequest request = GenerationArguments.parse(
                new DefaultApplicationArguments("--date=2026-10-01", "--rows=1000", "--seed=42"), TODAY);

        assertThat(request).isEqualTo(new GenerationRequest(LocalDate.of(2026, 10, 1), 1000, 42L));
    }

    @Test
    void fallsBackToTodayAndDefaultsWhenOptionsAreAbsent() {
        GenerationRequest request = GenerationArguments.parse(new DefaultApplicationArguments(), TODAY);

        assertThat(request.date()).isEqualTo(TODAY);
        assertThat(request.rows()).isEqualTo(GenerationArguments.DEFAULT_ROWS);
        assertThat(request.seed()).isEqualTo(GenerationArguments.DEFAULT_SEED);
    }

    @Test
    void acceptsNegativeSeeds() {
        GenerationRequest request = GenerationArguments.parse(new DefaultApplicationArguments("--seed=-7"), TODAY);

        assertThat(request.seed()).isEqualTo(-7L);
    }

    @Test
    void rejectsMalformedDate() {
        assertThatThrownBy(() -> GenerationArguments.parse(new DefaultApplicationArguments("--date=01/10/2026"), TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--date");
    }

    @Test
    void rejectsNonNumericRows() {
        assertThatThrownBy(() -> GenerationArguments.parse(new DefaultApplicationArguments("--rows=many"), TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--rows");
    }

    @Test
    void rejectsZeroRows() {
        assertThatThrownBy(() -> GenerationArguments.parse(new DefaultApplicationArguments("--rows=0"), TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows must be at least 1");
    }

    @Test
    void rejectsOptionWithoutValueOrGivenTwice() {
        assertThatThrownBy(() -> GenerationArguments.parse(new DefaultApplicationArguments("--seed"), TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--seed");
        assertThatThrownBy(() -> GenerationArguments.parse(
                new DefaultApplicationArguments("--seed=1", "--seed=2"), TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly once");
    }
}
