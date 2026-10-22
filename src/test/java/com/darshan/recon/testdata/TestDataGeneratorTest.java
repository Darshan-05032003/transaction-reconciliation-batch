package com.darshan.recon.testdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.TestcontainersConfiguration;
import com.darshan.recon.ingest.file.ControlTotals;
import com.darshan.recon.ingest.file.MalformedRecordException;
import com.darshan.recon.ingest.file.StatementDetail;
import com.darshan.recon.ingest.file.StatementHeader;
import com.darshan.recon.ingest.file.StatementRecordFormat;
import com.darshan.recon.ingest.file.StatementTrailer;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TestDataGeneratorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private TestDataGenerator generator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext context;

    @TempDir
    Path inbox;

    @Test
    void sameSeedProducesByteIdenticalFileAndManifest() throws IOException {
        GenerationRequest request = new GenerationRequest(DATE, 500, 42L);
        Path firstRun = inbox.resolve("first");
        Path secondRun = inbox.resolve("second");

        GenerationManifest first = generator.generate(request, firstRun);
        GenerationManifest second = generator.generate(request, secondRun);

        assertThat(second).isEqualTo(first);
        assertThat(Files.readAllBytes(secondRun.resolve(first.fileName())))
                .isEqualTo(Files.readAllBytes(firstRun.resolve(first.fileName())));
        String manifestName = TestDataGenerator.manifestFileName(first.fileName());
        assertThat(Files.readAllBytes(secondRun.resolve(manifestName)))
                .isEqualTo(Files.readAllBytes(firstRun.resolve(manifestName)));
    }

    @Test
    void differentSeedsProduceDifferentFiles() throws IOException {
        GenerationManifest withSeedOne = generator.generate(new GenerationRequest(DATE, 200, 1L), inbox);
        GenerationManifest withSeedTwo = generator.generate(new GenerationRequest(DATE, 200, 2L), inbox);

        assertThat(withSeedTwo.accountNumber()).isNotEqualTo(withSeedOne.accountNumber());
        assertThat(Files.readAllBytes(inbox.resolve(withSeedTwo.fileName())))
                .isNotEqualTo(Files.readAllBytes(inbox.resolve(withSeedOne.fileName())));
        assertThat(ledgerRowCount(withSeedOne.accountNumber())).isEqualTo(200);
        assertThat(ledgerRowCount(withSeedTwo.accountNumber())).isEqualTo(200);
        assertThat(jdbcTemplate.queryForList(
                "select attname from pg_stats where schemaname = current_schema() and tablename = 'ledger_transaction'",
                String.class)).contains("account_number", "value_date");
    }

    @Test
    void manifestIsReadableJsonWithIsoDateAndPlainTotal() throws IOException {
        GenerationManifest manifest = generator.generate(new GenerationRequest(DATE, 50, 3L), inbox);
        Path manifestFile = inbox.resolve(TestDataGenerator.manifestFileName(manifest.fileName()));

        String json = Files.readString(manifestFile);
        GenerationManifest reread = JSON.readValue(manifestFile, GenerationManifest.class);

        assertThat(json)
                .contains("\"statementDate\" : \"2026-10-01\"")
                .contains("\"declaredTotal\" : " + manifest.declaredTotal().toPlainString())
                .contains("\"seed\" : 3");
        assertThat(reread).isEqualTo(manifest);
        assertThat(manifestFile.getFileName().toString()).isEqualTo(
                "statement_BANK01_" + manifest.accountNumber() + "_2026-10-01.manifest.json");
    }

    @Test
    void rerunningTheSameSeedReplacesTheLedgerRowsInsteadOfDuplicatingThem() {
        GenerationRequest request = new GenerationRequest(DATE, 120, 9L);

        GenerationManifest manifest = generator.generate(request, inbox);
        generator.generate(request, inbox);

        assertThat(ledgerRowCount(manifest.accountNumber())).isEqualTo(120);
        assertThat(Files.exists(inbox.resolve(manifest.fileName() + ".part"))).isFalse();
    }

    @Test
    void manifestCountsMatchTheFileAndTheDatabase() throws IOException {
        GenerationManifest manifest = generator.generate(new GenerationRequest(DATE, 2000, 7L), inbox);
        Path file = inbox.resolve(manifest.fileName());

        assertThat(manifest.injected()).satisfies(injected -> {
            assertThat(injected.amountMismatchPaise()).isPositive();
            assertThat(injected.amountMismatchRupees()).isPositive();
            assertThat(injected.valueDateShift()).isPositive();
            assertThat(injected.missingInBank()).isPositive();
            assertThat(injected.extraInBank()).isPositive();
            assertThat(injected.duplicateReference()).isPositive();
            assertThat(injected.malformedBadAmount()).isPositive();
            assertThat(injected.malformedWrongColumnCount()).isPositive();
            assertThat(injected.malformedBadDate()).isPositive();
        });

        List<String> lines;
        try (Stream<String> stream = Files.lines(file)) {
            lines = stream.toList();
        }
        StatementHeader header = StatementRecordFormat.parseHeader(lines.getFirst());
        StatementTrailer trailer = StatementRecordFormat.parseTrailer(lines.getLast());
        List<String> detailLines = lines.subList(1, lines.size() - 1);
        ControlTotals recomputed = new ControlTotals();
        detailLines.forEach(recomputed::add);

        assertThat(header).isEqualTo(new StatementHeader("BANK01", manifest.accountNumber(), DATE));
        assertThat(detailLines).allMatch(StatementRecordFormat::isDetail);
        assertThat(recomputed.matches(trailer)).isTrue();
        assertThat(trailer.recordCount()).isEqualTo(manifest.declaredCount());
        assertThat(trailer.totalAmount()).isEqualByComparingTo(manifest.declaredTotal());

        Map<String, LedgerSide> ledger = ledgerRows(manifest.accountNumber());
        assertThat(ledger).hasSize(manifest.ledgerRows());
        assertThat(ledger.values()).allMatch(row -> row.valueDate().equals(DATE));

        ObservedBreaks observed = classify(detailLines, ledger);

        assertThat(observed.skipped).isEqualTo(manifest.expectedSkippedLines());
        assertThat(detailLines.size() - observed.skipped).isEqualTo(manifest.expectedLoadedLines());
        assertThat(observed.matched).isEqualTo(manifest.expectedMatched());
        assertThat(observed.amountPaise).isEqualTo(manifest.injected().amountMismatchPaise());
        assertThat(observed.amountRupees).isEqualTo(manifest.injected().amountMismatchRupees());
        assertThat(observed.amountPaise + observed.amountRupees).isEqualTo(manifest.expectedBreaks().amountMismatch());
        assertThat(observed.valueDateShift).isEqualTo(manifest.expectedBreaks().valueDateMismatch());
        assertThat(observed.missingInBank).isEqualTo(manifest.expectedBreaks().missingInBank());
        assertThat(observed.missingInLedger).isEqualTo(manifest.expectedBreaks().missingInLedger());
        assertThat(observed.duplicates).isEqualTo(manifest.expectedBreaks().duplicateInStatement());
        assertThat(observed.maxDateShiftDays).isBetween(1L, 3L);
    }

    @Test
    void runnerIsOnlyRegisteredUnderTheGenerateProfile() {
        assertThat(context.getBeansOfType(ApplicationRunner.class).values())
                .noneMatch(GenerateDataRunner.class::isInstance);
    }

    private int ledgerRowCount(String accountNumber) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from ledger_transaction where account_number = ? and booking_date = ?",
                Integer.class, accountNumber, DATE);
        return count == null ? 0 : count;
    }

    private Map<String, LedgerSide> ledgerRows(String accountNumber) {
        Map<String, LedgerSide> rows = new HashMap<>();
        jdbcTemplate.query("""
                select txn_reference, amount, value_date, currency
                from ledger_transaction where account_number = ? and booking_date = ?
                """, rs -> {
            assertThat(rs.getString("currency")).isEqualTo("INR");
            rows.put(rs.getString("txn_reference"), new LedgerSide(
                    rs.getBigDecimal("amount"), rs.getObject("value_date", LocalDate.class)));
        }, accountNumber, DATE);
        return rows;
    }

    // Independent reclassification of the file against the ledger; the manifest must agree with it.
    private static ObservedBreaks classify(List<String> detailLines, Map<String, LedgerSide> ledger) {
        ObservedBreaks observed = new ObservedBreaks();
        Map<String, List<StatementDetail>> byReference = new HashMap<>();
        for (String line : detailLines) {
            try {
                StatementDetail detail = StatementRecordFormat.parseDetail(line);
                byReference.computeIfAbsent(detail.bankReference(), r -> new java.util.ArrayList<>()).add(detail);
            } catch (MalformedRecordException e) {
                observed.skipped++;
            }
        }
        byReference.forEach((reference, occurrences) -> {
            LedgerSide ledgerSide = ledger.get(reference);
            if (ledgerSide == null) {
                observed.missingInLedger += occurrences.size();
            } else if (occurrences.size() > 1) {
                observed.duplicates++;
            } else {
                observed.classifyPaired(ledgerSide, occurrences.getFirst());
            }
        });
        observed.missingInBank = (int) ledger.keySet().stream().filter(r -> !byReference.containsKey(r)).count();
        return observed;
    }

    private record LedgerSide(BigDecimal amount, LocalDate valueDate) {
    }

    private static final class ObservedBreaks {
        int skipped;
        int matched;
        int amountPaise;
        int amountRupees;
        int valueDateShift;
        int missingInBank;
        int missingInLedger;
        int duplicates;
        long maxDateShiftDays;

        void classifyPaired(LedgerSide ledger, StatementDetail bank) {
            BigDecimal difference = bank.amount().subtract(ledger.amount()).abs();
            if (difference.signum() != 0) {
                if (difference.compareTo(BigDecimal.ONE) < 0) {
                    amountPaise++;
                } else {
                    amountRupees++;
                }
            } else if (!bank.valueDate().equals(ledger.valueDate())) {
                valueDateShift++;
                maxDateShiftDays = Math.max(maxDateShiftDays,
                        Math.abs(java.time.temporal.ChronoUnit.DAYS.between(ledger.valueDate(), bank.valueDate())));
            } else {
                matched++;
            }
        }
    }
}
