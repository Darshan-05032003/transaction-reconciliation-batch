package com.darshan.recon.ingest.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

// Guards the hand-made fixtures: later features rely on exactly these properties.
class StatementFixturesTest {

    @Test
    void cleanFixtureParsesCompletelyAndMatchesItsTrailer() throws Exception {
        List<String> lines = fixture("statement_clean.csv");
        List<String> details = details(lines);

        assertThat(StatementRecordFormat.parseHeader(lines.getFirst()))
                .isEqualTo(new StatementHeader("BANK01", "000123456789", LocalDate.of(2026, 10, 1)));
        assertThat(details).hasSize(5).allSatisfy(line ->
                assertThat(StatementRecordFormat.parseDetail(line).currency()).isEqualTo("INR"));
        assertThat(totalsOf(details).matches(StatementRecordFormat.parseTrailer(lines.getLast()))).isTrue();
    }

    @Test
    void badLinesFixtureHasThreeMalformedLinesButConsistentTrailer() throws Exception {
        List<String> lines = fixture("statement_bad_lines.csv");
        List<String> details = details(lines);

        assertThat(details).hasSize(7);
        assertThatThrownBy(() -> StatementRecordFormat.parseDetail(details.get(4)))
                .isInstanceOf(MalformedRecordException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> StatementRecordFormat.parseDetail(details.get(5)))
                .isInstanceOf(MalformedRecordException.class).hasMessageContaining("expected 6 fields");
        assertThatThrownBy(() -> StatementRecordFormat.parseDetail(details.get(6)))
                .isInstanceOf(MalformedRecordException.class).hasMessageContaining("valueDate");
        details.subList(0, 4).forEach(StatementRecordFormat::parseDetail);

        StatementTrailer trailer = StatementRecordFormat.parseTrailer(lines.getLast());
        assertThat(trailer.recordCount()).isEqualTo(7);
        assertThat(trailer.totalAmount()).isEqualByComparingTo("1620.85");
        assertThat(totalsOf(details).matches(trailer)).isTrue();
    }

    @Test
    void wrongTrailerFixtureHasCorrectCountButWrongTotal() throws Exception {
        List<String> lines = fixture("statement_wrong_trailer.csv");
        List<String> details = details(lines);
        details.forEach(StatementRecordFormat::parseDetail);

        StatementTrailer trailer = StatementRecordFormat.parseTrailer(lines.getLast());
        ControlTotals totals = totalsOf(details);

        assertThat(totals.recordCount()).isEqualTo(trailer.recordCount());
        assertThat(totals.totalAmount()).isEqualByComparingTo("600.00");
        assertThat(trailer.totalAmount()).isEqualByComparingTo("650.00");
        assertThat(totals.matches(trailer)).isFalse();
    }

    private static List<String> fixture(String name) throws IOException, URISyntaxException {
        Path path = Path.of(StatementFixturesTest.class.getResource("/fixtures/" + name).toURI());
        return Files.readAllLines(path);
    }

    private static List<String> details(List<String> lines) {
        return lines.subList(1, lines.size() - 1);
    }

    private static ControlTotals totalsOf(List<String> details) {
        ControlTotals totals = new ControlTotals();
        details.forEach(totals::add);
        return totals;
    }
}
