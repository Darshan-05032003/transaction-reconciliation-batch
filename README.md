# transaction-reconciliation-batch

Spring Batch application that reconciles daily bank statement files against the internal ledger, classifies the breaks and lets operations resolve them.

## Running locally

Requirements: Java 21 and Docker.

```bash
cp .env.example .env
docker compose up -d
./mvnw spring-boot:run
```

The application starts with the `dev` profile on port 8083 and runs the Flyway migrations against the PostgreSQL container on port 5434.

## Configuration

Every setting is read from environment variables; `.env.example` lists them with their defaults. The reconciliation settings live under `app.recon.*` and are validated at startup, so a missing folder or a chunk size of zero fails the boot instead of a job at midnight.

Profiles: `dev` (default, verbose logging), `test` (used by the test suite with Testcontainers) and `prod` (no default database credentials, fails fast when they are missing).

## Statement file format

A statement file is a UTF-8 CSV with one header, any number of data records and one trailer. Fields never contain commas, so there is no quoting.

```text
HDR,<bankCode>,<accountNumber>,<statementDate>
DTL,<bankReference>,<valueDate>,<amount>,<currency>,<description>
TRL,<recordCount>,<totalAmount>
```

| Field | Format |
|---|---|
| `statementDate`, `valueDate` | ISO date, `yyyy-MM-dd` |
| `amount`, `totalAmount` | decimal with at most two places, optional leading minus for reversals, no thousands separator |
| `currency` | three upper-case letters (ISO 4217) |
| `bankReference` | the counterparty's transaction reference; it is matched against `ledger_transaction.txn_reference` |

Control totals: `recordCount` is the number of `DTL` records in the file, malformed ones included. `totalAmount` is the sum of the amount field (the fourth column) of every `DTL` record whose amount field is a valid amount; a record whose amount cannot be read contributes nothing to the total. The same rule (`ControlTotals`) builds the trailer in the test data generator and recomputes it during ingestion, so a file can pass its control totals and still contain lines that the ingest job skips and reports. A trailer that cannot be reproduced from the file's content is treated as a rejected file.

Hand-made examples live in `src/test/resources/fixtures/`: a clean file, a file with a bad amount, a wrong column count and a bad date, and a file whose trailer total is wrong.

## Generating test data

The `generate` profile runs a one-shot command that inserts ledger transactions for a date and writes the matching statement file into the inbox folder, with deliberate breaks: amount differences in paise and in whole rupees, value dates shifted by one to three days, ledger transactions missing from the statement, statement lines with no ledger transaction, duplicated bank references, and malformed lines (bad amount, wrong column count, bad date). Malformed lines always use references that do not exist in the ledger, so skipping them during ingestion does not change the expected breaks.

```bash
scripts/generate_data.sh -d 2026-10-01 -n 1000 -s 42
```

The script builds the jar if `target/` has none (rebuild with `./mvnw -DskipTests package` after code changes), reads `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` from the environment like the application, logs to stdout and `logs/generate_data.log`, and exits with 2 for bad arguments, 3 when Java 21 is missing, 4 when the build fails and 5 when generation fails. Without the script: `java -jar target/*.jar --spring.profiles.active=generate --date=2026-10-01 --rows=1000 --seed=42`.

The data is deterministic: the same date, row count and seed always produce the same ledger rows, a byte-identical statement file and a byte-identical manifest. The account number is derived from the seed, and running the same seed again replaces that account's ledger rows for the date instead of duplicating them. Ledger rows are inserted with JDBC batches of `RECON_CHUNK_SIZE` and the file is streamed line by line, so a million rows do not need a million objects in memory. The statement file appears under its final name only after the ledger rows are committed.

Next to the file the generator writes `<file>.manifest.json` with the exact count of every injected break, the expected number of matched transactions, the number of lines the ingest job is expected to load and skip, and the declared control totals. Tests of the ingest and matching jobs assert against this manifest instead of against hand-counted expectations. `data/` is ignored by git.

## Ingesting a statement file

`ingestStatementJob` takes one job parameter, `filePath`, and runs three steps:

1. `computeChecksum` streams the file through SHA-256. If a `statement_file` row with that checksum already exists, the job ends with exit status `DUPLICATE` and changes nothing. Otherwise a row with status `RECEIVED` is inserted.
2. `validateControlTotals` reads the file once, parses the header and the trailer and recomputes the control totals from the data records. On a mismatch the file is marked `REJECTED` with the reason, the job ends with exit status `REJECTED` and nothing is loaded. Otherwise the header fields and the declared count and total are stored on the `statement_file` row.
3. `loadStatementLines` reads the data records in chunks of `RECON_CHUNK_SIZE`, parses and validates each one (required fields, decimal amount, ISO date, three-letter currency) and inserts them into `statement_line` with JDBC batches, one transaction per chunk. Records that cannot be parsed are skipped and appended to `data/error/<file>.errors.csv` with their line number, the reason and the raw line, up to `RECON_SKIP_LIMIT` per run; one more bad record fails the job and the file is marked `FAILED` with the reason. Transient database errors are retried three times.

When the job completes, the file is marked `VALIDATED` and one summary line is logged with the declared, read, written and skipped counts and the duration. A failed execution can be restarted and resumes after the last committed chunk. Launching the same `filePath` again is refused because that job instance already completed; the same content under another name ends with `DUPLICATE`. Nothing runs at boot (`spring.batch.job.enabled=false`); the job is started through the `JobOperator`.

## Testing

```bash
./mvnw -B clean verify
```

Integration tests start a disposable PostgreSQL 16 with Testcontainers, so Docker must be running.
