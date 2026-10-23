package com.darshan.recon.testdata;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.common.config.ReconProperties.Folders;
import com.darshan.recon.testdata.GenerationManifest.ExpectedBreaks;
import com.darshan.recon.testdata.GenerationManifest.InjectedBreaks;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class GenerateDataRunnerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T02:15:00Z"), ZoneOffset.UTC);
    private static final Path INBOX = Path.of("build-test-inbox");

    private final TestDataGenerator generator = mock(TestDataGenerator.class);
    private final GenerateDataRunner runner = new GenerateDataRunner(generator, properties(), CLOCK);

    @Test
    void passesTheParsedArgumentsAndTheInboxToTheGenerator() {
        when(generator.generate(any(), any())).thenReturn(manifest());

        runner.run(new DefaultApplicationArguments("--date=2026-10-01", "--rows=250", "--seed=9"));

        verify(generator).generate(new GenerationRequest(LocalDate.of(2026, 10, 1), 250, 9L), INBOX);
    }

    @Test
    void defaultsTheDateToTodayInTheApplicationClock() {
        when(generator.generate(any(), any())).thenReturn(manifest());

        runner.run(new DefaultApplicationArguments("--rows=10"));

        verify(generator).generate(new GenerationRequest(LocalDate.of(2026, 10, 23), 10, 42L), INBOX);
    }

    @Test
    void badArgumentsFailBeforeAnythingIsGenerated() {
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments("--rows=ten")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--rows must be an integer");
        org.mockito.Mockito.verifyNoInteractions(generator);
    }

    private static ReconProperties properties() {
        return new ReconProperties(new Folders(INBOX, Path.of("a"), Path.of("f"), Path.of("e"), Path.of("r")),
                50, 2, 10, new BigDecimal("0.00"), 3, "-", new BigDecimal("1000.00"));
    }

    private static GenerationManifest manifest() {
        return new GenerationManifest("statement.csv", LocalDate.of(2026, 10, 1), 9L, "BANK01", "000123456789",
                250, 255, new BigDecimal("1000.00"), 253, 2, 230,
                new InjectedBreaks(1, 2, 3, 4, 5, 6, 1, 1, 0), new ExpectedBreaks(3, 3, 4, 5, 6));
    }
}
