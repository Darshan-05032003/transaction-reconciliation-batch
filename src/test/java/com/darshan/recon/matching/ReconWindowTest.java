package com.darshan.recon.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReconWindowTest {

    @Test
    void surroundsTheStatementDateByTheToleranceOnBothSides() {
        ReconWindow window = ReconWindow.around(LocalDate.of(2026, 10, 1), 3);

        assertThat(window).isEqualTo(new ReconWindow(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4)));
        assertThat(ReconWindow.around(LocalDate.of(2026, 10, 1), 0))
                .isEqualTo(new ReconWindow(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1)));
    }

    @Test
    void roundTripsThroughIsoStringsForTheExecutionContext() {
        ReconWindow window = ReconWindow.around(LocalDate.of(2026, 10, 1), 3);

        assertThat(ReconWindow.parse(window.from().toString(), window.to().toString())).isEqualTo(window);
    }

    @Test
    void rejectsAnInvertedWindow() {
        assertThatThrownBy(() -> new ReconWindow(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
