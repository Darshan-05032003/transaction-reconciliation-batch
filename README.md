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

## Testing

```bash
./mvnw -B clean verify
```

Integration tests start a disposable PostgreSQL 16 with Testcontainers, so Docker must be running.
