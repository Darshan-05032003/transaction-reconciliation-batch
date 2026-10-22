package com.darshan.recon.testdata;

import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.ingest.file.ControlTotals;
import com.darshan.recon.ingest.file.StatementDetail;
import com.darshan.recon.ingest.file.StatementHeader;
import com.darshan.recon.ingest.file.StatementRecordFormat;
import com.darshan.recon.testdata.GenerationManifest.ExpectedBreaks;
import com.darshan.recon.testdata.GenerationManifest.InjectedBreaks;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TestDataGenerator {

    public static final String BANK_CODE = "BANK01";
    public static final String CURRENCY = "INR";
    public static final String MANIFEST_SUFFIX = ".manifest.json";

    private static final Logger log = LoggerFactory.getLogger(TestDataGenerator.class);

    // Rates per ledger row. Together they break roughly one row in twelve, which is far
    // more than a real nightly file but keeps every break type present in small samples.
    private static final double AMOUNT_PAISE_RATE = 0.015;
    private static final double AMOUNT_RUPEES_RATE = 0.010;
    private static final double VALUE_DATE_SHIFT_RATE = 0.020;
    private static final double MISSING_IN_BANK_RATE = 0.015;
    private static final double DUPLICATE_RATE = 0.010;
    private static final double EXTRA_IN_BANK_RATE = 0.015;
    private static final double MALFORMED_RATE = 0.009;

    private static final long MIN_PAISE = 100L;
    private static final long MAX_PAISE = 5_000_000L;
    private static final int REVERSAL_PERCENT = 5;
    private static final int MAX_RUPEE_DIFFERENCE = 500;
    private static final int MAX_DATE_SHIFT_DAYS = 3;

    private static final List<String> CHANNELS = List.of("NEFT", "IMPS", "UPI", "RTGS", "POS", "CHQ");
    private static final List<String> COUNTERPARTIES = List.of(
            "Ravi Stores", "Meera Textiles", "Anand Pharma", "Sharma Traders", "City Cab",
            "Sunrise Bakery", "Patel Hardware", "Nair Electronics", "Blue Dart Courier", "Metro Grocers");
    private static final List<String> BAD_AMOUNTS = List.of("12.5O", "N/A", "100.005");
    private static final List<String> BAD_DATES = List.of("01/10/2026", "20261001", "2026-13-45");

    private static final String DELETE_LEDGER = """
            delete from ledger_transaction where account_number = ? and booking_date = ?
            """;
    private static final String INSERT_LEDGER = """
            insert into ledger_transaction
                (txn_reference, account_number, amount, currency, booking_date, value_date, description, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .defaultPrettyPrinter(new DefaultPrettyPrinter()
                    .withObjectIndenter(new DefaultIndenter("  ", "\n")))
            .build();

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int chunkSize;

    public TestDataGenerator(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                             Clock clock, ReconProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.chunkSize = properties.chunkSize();
    }

    public GenerationManifest generate(GenerationRequest request, Path targetDir) {
        Random random = new Random(request.seed());
        String accountNumber = String.format("%012d", random.nextLong(0, 1_000_000_000_000L));
        String fileName = "statement_%s_%s_%s.csv".formatted(BANK_CODE, accountNumber, request.date());
        Path partFile = targetDir.resolve(fileName + ".part");
        Path statementFile = targetDir.resolve(fileName);
        Path manifestFile = targetDir.resolve(manifestFileName(fileName));

        try {
            Files.createDirectories(targetDir);
            GenerationManifest manifest = transactionTemplate.execute(status ->
                    new Run(request, random, accountNumber, fileName, partFile).execute());
            // The statement becomes visible only after the ledger rows are committed, so a
            // poller can never pick up a file whose ledger side does not exist yet.
            Files.move(partFile, statementFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            JSON.writeValue(manifestFile, manifest);
            log.info("Generated {} ledger rows and {} statement records for account ending {} into {}",
                    manifest.ledgerRows(), manifest.declaredCount(), maskAccount(accountNumber), statementFile);
            return manifest;
        } catch (IOException e) {
            deleteQuietly(partFile);
            throw new UncheckedIOException("Could not write statement file " + statementFile, e);
        } catch (RuntimeException e) {
            deleteQuietly(partFile);
            throw e;
        }
    }

    public static String manifestFileName(String statementFileName) {
        return statementFileName.replaceFirst("\\.csv$", "") + MANIFEST_SUFFIX;
    }

    private static String maskAccount(String accountNumber) {
        return accountNumber.substring(accountNumber.length() - 4);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete partial file {}", file);
        }
    }

    private enum Fate { MATCHED, AMOUNT_PAISE, AMOUNT_RUPEES, VALUE_DATE_SHIFT, MISSING_IN_BANK, DUPLICATE }

    private enum Malformation { BAD_AMOUNT, WRONG_COLUMN_COUNT, BAD_DATE }

    private record LedgerRow(String reference, BigDecimal amount, LocalDate valueDate, String description) {
    }

    // One generation pass: a single sequential walk over the rows so that every random draw
    // happens in a fixed order and the ledger inserts and the file lines stay in step.
    private final class Run {

        private final GenerationRequest request;
        private final Random random;
        private final String accountNumber;
        private final String fileName;
        private final Path partFile;
        private final ControlTotals totals = new ControlTotals();
        private final List<Object[]> ledgerBuffer;
        private final OffsetDateTime createdAt;

        private int nextBankOnlySequence;
        private int matched;
        private int amountPaise;
        private int amountRupees;
        private int valueDateShift;
        private int missingInBank;
        private int extraInBank;
        private int duplicateReference;
        private int malformedBadAmount;
        private int malformedWrongColumnCount;
        private int malformedBadDate;

        Run(GenerationRequest request, Random random, String accountNumber, String fileName, Path partFile) {
            this.request = request;
            this.random = random;
            this.accountNumber = accountNumber;
            this.fileName = fileName;
            this.partFile = partFile;
            this.ledgerBuffer = new ArrayList<>(chunkSize);
            this.createdAt = OffsetDateTime.now(clock);
            this.nextBankOnlySequence = request.rows() + 1;
        }

        GenerationManifest execute() {
            int deleted = jdbcTemplate.update(DELETE_LEDGER, accountNumber, request.date());
            if (deleted > 0) {
                log.info("Replaced {} existing ledger rows for account ending {} on {}",
                        deleted, maskAccount(accountNumber), request.date());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(partFile, StandardCharsets.UTF_8)) {
                writeLine(writer, StatementRecordFormat.formatHeader(
                        new StatementHeader(BANK_CODE, accountNumber, request.date())));
                for (int sequence = 1; sequence <= request.rows(); sequence++) {
                    LedgerRow row = nextLedgerRow(sequence);
                    bufferLedgerRow(row);
                    writeBankVersion(writer, row);
                    maybeWriteExtraLine(writer);
                    maybeWriteMalformedLine(writer);
                }
                flushLedger();
                writeLine(writer, StatementRecordFormat.formatTrailer(totals.toTrailer()));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not write " + partFile, e);
            }
            return manifest();
        }

        private LedgerRow nextLedgerRow(int sequence) {
            BigDecimal amount = BigDecimal.valueOf(random.nextLong(MIN_PAISE, MAX_PAISE), 2);
            if (random.nextInt(100) < REVERSAL_PERCENT) {
                amount = amount.negate();
            }
            String description = pick(CHANNELS) + "/" + pick(COUNTERPARTIES);
            return new LedgerRow(reference(sequence), amount, request.date(), description);
        }

        private String reference(int sequence) {
            return "TXN%s-%s-%07d".formatted(
                    request.date().toString().replace("-", ""), accountNumber, sequence);
        }

        private void bufferLedgerRow(LedgerRow row) {
            ledgerBuffer.add(new Object[] {
                    row.reference(), accountNumber, row.amount(), CURRENCY,
                    request.date(), row.valueDate(), row.description(), createdAt});
            if (ledgerBuffer.size() == chunkSize) {
                flushLedger();
            }
        }

        private void flushLedger() {
            if (ledgerBuffer.isEmpty()) {
                return;
            }
            jdbcTemplate.batchUpdate(INSERT_LEDGER, ledgerBuffer);
            ledgerBuffer.clear();
        }

        private void writeBankVersion(BufferedWriter writer, LedgerRow row) throws IOException {
            switch (drawFate()) {
                case MATCHED -> {
                    matched++;
                    writeDetail(writer, row);
                }
                case AMOUNT_PAISE -> {
                    amountPaise++;
                    BigDecimal paise = BigDecimal.valueOf(random.nextInt(1, 100), 2);
                    writeDetail(writer, row.reference(), row.amount().add(signed(paise)), row.valueDate(), row.description());
                }
                case AMOUNT_RUPEES -> {
                    amountRupees++;
                    BigDecimal rupees = BigDecimal.valueOf(random.nextInt(1, MAX_RUPEE_DIFFERENCE + 1));
                    writeDetail(writer, row.reference(), row.amount().add(signed(rupees)), row.valueDate(), row.description());
                }
                case VALUE_DATE_SHIFT -> {
                    valueDateShift++;
                    int days = random.nextInt(1, MAX_DATE_SHIFT_DAYS + 1);
                    LocalDate shifted = random.nextBoolean() ? row.valueDate().plusDays(days) : row.valueDate().minusDays(days);
                    writeDetail(writer, row.reference(), row.amount(), shifted, row.description());
                }
                case MISSING_IN_BANK -> missingInBank++;
                case DUPLICATE -> {
                    duplicateReference++;
                    writeDetail(writer, row);
                    writeDetail(writer, row);
                }
            }
        }

        private Fate drawFate() {
            double draw = random.nextDouble();
            double threshold = AMOUNT_PAISE_RATE;
            if (draw < threshold) {
                return Fate.AMOUNT_PAISE;
            }
            if (draw < (threshold += AMOUNT_RUPEES_RATE)) {
                return Fate.AMOUNT_RUPEES;
            }
            if (draw < (threshold += VALUE_DATE_SHIFT_RATE)) {
                return Fate.VALUE_DATE_SHIFT;
            }
            if (draw < (threshold += MISSING_IN_BANK_RATE)) {
                return Fate.MISSING_IN_BANK;
            }
            if (draw < threshold + DUPLICATE_RATE) {
                return Fate.DUPLICATE;
            }
            return Fate.MATCHED;
        }

        private void maybeWriteExtraLine(BufferedWriter writer) throws IOException {
            if (random.nextDouble() >= EXTRA_IN_BANK_RATE) {
                return;
            }
            extraInBank++;
            LedgerRow bankOnly = nextLedgerRow(nextBankOnlySequence++);
            writeDetail(writer, bankOnly);
        }

        private void maybeWriteMalformedLine(BufferedWriter writer) throws IOException {
            if (random.nextDouble() >= MALFORMED_RATE) {
                return;
            }
            LedgerRow base = nextLedgerRow(nextBankOnlySequence++);
            String valueDate = base.valueDate().toString();
            String amount = StatementRecordFormat.formatAmount(base.amount());
            String line = switch (pick(List.of(Malformation.values()))) {
                case BAD_AMOUNT -> {
                    malformedBadAmount++;
                    yield detailLine(base.reference(), valueDate, pick(BAD_AMOUNTS), CURRENCY, base.description());
                }
                case WRONG_COLUMN_COUNT -> {
                    malformedWrongColumnCount++;
                    yield random.nextBoolean()
                            ? String.join(",", StatementRecordFormat.DETAIL_TAG, base.reference(), valueDate, amount, CURRENCY)
                            : detailLine(base.reference(), valueDate, amount, CURRENCY, base.description()) + ",EXTRA";
                }
                case BAD_DATE -> {
                    malformedBadDate++;
                    yield detailLine(base.reference(), pick(BAD_DATES), amount, CURRENCY, base.description());
                }
            };
            writeDetailLine(writer, line);
        }

        private void writeDetail(BufferedWriter writer, LedgerRow row) throws IOException {
            writeDetail(writer, row.reference(), row.amount(), row.valueDate(), row.description());
        }

        private void writeDetail(BufferedWriter writer, String reference, BigDecimal amount,
                                 LocalDate valueDate, String description) throws IOException {
            writeDetailLine(writer, StatementRecordFormat.formatDetail(
                    new StatementDetail(reference, valueDate, amount, CURRENCY, description)));
        }

        private void writeDetailLine(BufferedWriter writer, String line) throws IOException {
            totals.add(line);
            writeLine(writer, line);
        }

        private BigDecimal signed(BigDecimal difference) {
            return random.nextBoolean() ? difference : difference.negate();
        }

        private <T> T pick(List<T> options) {
            return options.get(random.nextInt(options.size()));
        }

        private GenerationManifest manifest() {
            InjectedBreaks injected = new InjectedBreaks(amountPaise, amountRupees, valueDateShift, missingInBank,
                    extraInBank, duplicateReference, malformedBadAmount, malformedWrongColumnCount, malformedBadDate);
            ExpectedBreaks expected = new ExpectedBreaks(amountPaise + amountRupees, valueDateShift,
                    missingInBank, extraInBank, duplicateReference);
            return new GenerationManifest(fileName, request.date(), request.seed(), BANK_CODE, accountNumber,
                    request.rows(), totals.recordCount(), totals.totalAmount(),
                    totals.recordCount() - injected.malformedTotal(), injected.malformedTotal(),
                    matched, injected, expected);
        }
    }

    private static String detailLine(String reference, String valueDate, String amount, String currency,
                                     String description) {
        return String.join(StatementRecordFormat.SEPARATOR, StatementRecordFormat.DETAIL_TAG,
                reference, valueDate, amount, currency, description);
    }

    // A fixed line separator keeps the output byte-identical regardless of the platform.
    private static void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
    }
}
