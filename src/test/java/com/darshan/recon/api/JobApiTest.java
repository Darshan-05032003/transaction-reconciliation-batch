package com.darshan.recon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class JobApiTest extends ApiTestBase {

    private static final String CLEAN_FIXTURE = "statement_clean.csv";

    @Test
    void ingestAndReconcileRunAsynchronouslyAndCanBeFollowedThroughTheApi() throws Exception {
        copyFixtureToInbox(CLEAN_FIXTURE, "api_clean.csv");

        MvcTestResult launched = ingest("api_clean.csv", OPERATOR);
        assertThat(launched).hasStatus(202);
        long ingestExecutionId = executionId(launched);
        assertThat(launched).hasHeader(HttpHeaders.LOCATION,
                "http://localhost/api/v1/jobs/executions/" + ingestExecutionId);
        assertThat(launched).bodyJson()
                .hasPathSatisfying("$.jobName", j -> j.assertThat().isEqualTo("ingestStatementJob"))
                .hasPathSatisfying("$.status", s -> s.assertThat().asString().isIn("STARTING", "STARTED", "COMPLETED"));

        MvcTestResult ingestExecution = awaitFinished(ingestExecutionId);
        assertThat(ingestExecution).bodyJson()
                .hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo("COMPLETED"))
                .hasPathSatisfying("$.exitCode", e -> e.assertThat().isEqualTo("COMPLETED"))
                .hasPathSatisfying("$.parameters.launchedBy", l -> l.assertThat().isEqualTo(OPERATOR))
                .hasPathSatisfying("$.parameters.filePath", f -> f.assertThat().asString().endsWith("api_clean.csv"))
                .hasPathSatisfying("$.durationMs", d -> d.assertThat().isNotNull())
                .hasPathSatisfying("$.steps[*].name", names -> names.assertThat().asArray()
                        .containsExactly("computeChecksum", "validateControlTotals", "loadStatementLines"))
                .hasPathSatisfying("$.steps[2].readCount", r -> r.assertThat().isEqualTo(5))
                .hasPathSatisfying("$.steps[2].writeCount", w -> w.assertThat().isEqualTo(5))
                .hasPathSatisfying("$.steps[2].skipCount", s -> s.assertThat().isEqualTo(0))
                .hasPathSatisfying("$.steps[2].commitCount", c -> c.assertThat().asNumber().satisfies(n -> assertThat(n.longValue()).isGreaterThanOrEqualTo(1)))
                .hasPathSatisfying("$.steps[2].rollbackCount", r -> r.assertThat().isEqualTo(0));
        long fileId = jdbcTemplate.queryForObject("select id from statement_file", Long.class);
        assertThat(ingestExecution).bodyJson()
                .hasPathSatisfying("$.statementFileId", f -> number(f.assertThat(), String.valueOf(fileId)))
                .hasPathSatisfying("$.reconRunId", r -> r.assertThat().isNull());

        MvcTestResult reconcileLaunched = mvc.post().uri("/api/v1/jobs/reconcile/{id}", fileId)
                .with(as(OPERATOR)).exchange();
        assertThat(reconcileLaunched).hasStatus(202);
        long reconcileExecutionId = executionId(reconcileLaunched);
        MvcTestResult reconcileExecution = awaitFinished(reconcileExecutionId);
        assertThat(reconcileExecution).bodyJson()
                .hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo("COMPLETED"))
                .hasPathSatisfying("$.jobName", j -> j.assertThat().isEqualTo("reconcileJob"))
                .hasPathSatisfying("$.steps[*].name", names -> names.assertThat().asArray()
                        .contains("createReconRun", "matchCandidates", "finaliseReconRun", "writeBreakReport", "writeRunSummary"));
        long runId = jdbcTemplate.queryForObject("select id from recon_run", Long.class);
        assertThat(reconcileExecution).bodyJson()
                .hasPathSatisfying("$.statementFileId", f -> number(f.assertThat(), String.valueOf(fileId)))
                .hasPathSatisfying("$.reconRunId", r -> number(r.assertThat(), String.valueOf(runId)));

        MvcTestResult summary = mvc.get().uri("/api/v1/recon-runs/{id}/summary", runId).with(as(VIEWER)).exchange();
        assertThat(summary).hasStatusOk();
        assertThat(summary).bodyJson()
                .hasPathSatisfying("$.reconRunId", r -> number(r.assertThat(), String.valueOf(runId)))
                .hasPathSatisfying("$.statementFileId", f -> number(f.assertThat(), String.valueOf(fileId)))
                .hasPathSatisfying("$.fileName", f -> f.assertThat().isEqualTo("api_clean.csv"))
                .hasPathSatisfying("$.statementDate", d -> d.assertThat().isEqualTo("2026-10-01"))
                .hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo("COMPLETED"))
                .hasPathSatisfying("$.matchedCount", m -> m.assertThat().isEqualTo(0))
                .hasPathSatisfying("$.breakCount", b -> b.assertThat().isEqualTo(5))
                .hasPathSatisfying("$.matchRatePercent", r -> number(r.assertThat(), "0.00"))
                .hasPathSatisfying("$.breaksByType.MISSING_IN_LEDGER", n -> n.assertThat().isEqualTo(5))
                .hasPathSatisfying("$.breaksByType.AMOUNT_MISMATCH", n -> n.assertThat().isEqualTo(0))
                .hasPathSatisfying("$.breaksByStatus.OPEN", n -> n.assertThat().isEqualTo(5))
                .hasPathSatisfying("$.breaksByStatus.RESOLVED", n -> n.assertThat().isEqualTo(0));

        assertThat(Files.exists(properties.folders().reports().resolve("recon_2026-10-01_" + runId + ".csv"))).isTrue();
        assertThat(Files.exists(properties.folders().reports().resolve("recon_2026-10-01_" + runId + "_summary.csv"))).isTrue();

        assertThat(ingest("api_clean.csv", OPERATOR)).hasStatus(409);
        MvcTestResult secondRun = mvc.post().uri("/api/v1/jobs/reconcile/{id}", fileId).with(as(OPERATOR)).exchange();
        assertThat(secondRun).hasStatus(409).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(secondRun).bodyJson().extractingPath("$.detail").asString().contains("rerun=true");

        MvcTestResult rerun = mvc.post().uri("/api/v1/jobs/reconcile/{id}?rerun=true&gridSize=1", fileId)
                .with(as(OPERATOR)).exchange();
        assertThat(rerun).hasStatus(202);
        MvcTestResult rerunExecution = awaitFinished(executionId(rerun));
        assertThat(rerunExecution).bodyJson()
                .hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo("COMPLETED"))
                .hasPathSatisfying("$.parameters.rerun", r -> r.assertThat().asString().startsWith(OPERATOR + "@"))
                .hasPathSatisfying("$.parameters.gridSize", g -> g.assertThat().isEqualTo("1"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from recon_run", Integer.class)).isEqualTo(2);
    }

    @Test
    void breaksListingIsPagedFilteredAndCapped() throws Exception {
        long runId = reconcileCleanFixture();

        MvcTestResult firstPage = mvc.get().uri("/api/v1/recon-runs/{id}/breaks?size=2", runId).with(as(VIEWER)).exchange();
        assertThat(firstPage).hasStatusOk();
        assertThat(firstPage).bodyJson()
                .hasPathSatisfying("$.content", c -> c.assertThat().asArray().hasSize(2))
                .hasPathSatisfying("$.page", p -> p.assertThat().isEqualTo(0))
                .hasPathSatisfying("$.size", s -> s.assertThat().isEqualTo(2))
                .hasPathSatisfying("$.totalElements", t -> t.assertThat().isEqualTo(5))
                .hasPathSatisfying("$.totalPages", t -> t.assertThat().isEqualTo(3))
                .hasPathSatisfying("$.content[0].breakType", t -> t.assertThat().isEqualTo("MISSING_IN_LEDGER"))
                .hasPathSatisfying("$.content[0].severity", s -> s.assertThat().isEqualTo("ERROR"))
                .hasPathSatisfying("$.content[0].bankReference", r -> r.assertThat().asString().startsWith("TXN20261001"))
                .hasPathSatisfying("$.content[0].ledgerTxnId", l -> l.assertThat().isNull())
                .hasPathSatisfying("$.content[0].bankAmount", a -> a.assertThat().isNotNull())
                .hasPathSatisfying("$.content[0].status", s -> s.assertThat().isEqualTo("OPEN"));

        // Breaks are written in partition order, so paging by id must still cover every line exactly once.
        List<Integer> lineNumbers = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            MvcTestResult result = mvc.get().uri("/api/v1/recon-runs/{id}/breaks?size=2&page={page}", runId, page)
                    .with(as(VIEWER)).exchange();
            assertThat(result).hasStatusOk();
            lineNumbers.addAll(JsonPath.read(result.getResponse().getContentAsString(), "$.content[*].lineNumber"));
        }
        assertThat(lineNumbers).containsExactlyInAnyOrder(2, 3, 4, 5, 6);

        MvcTestResult filtered = mvc.get()
                .uri("/api/v1/recon-runs/{id}/breaks?type=MISSING_IN_LEDGER&status=OPEN", runId)
                .with(as(VIEWER)).exchange();
        assertThat(filtered).bodyJson().hasPathSatisfying("$.totalElements", t -> t.assertThat().isEqualTo(5));
        MvcTestResult none = mvc.get()
                .uri("/api/v1/recon-runs/{id}/breaks?type=AMOUNT_MISMATCH", runId)
                .with(as(VIEWER)).exchange();
        assertThat(none).bodyJson()
                .hasPathSatisfying("$.totalElements", t -> t.assertThat().isEqualTo(0))
                .hasPathSatisfying("$.totalPages", t -> t.assertThat().isEqualTo(0));

        assertThat(mvc.get().uri("/api/v1/recon-runs/{id}/breaks?size=201", runId).with(as(VIEWER)).exchange())
                .hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/recon-runs/{id}/breaks?page=-1", runId).with(as(VIEWER)).exchange())
                .hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/recon-runs/{id}/breaks?type=BOGUS", runId).with(as(VIEWER)).exchange())
                .hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/recon-runs/{id}/breaks", runId + 1000).with(as(VIEWER)).exchange())
                .hasStatus(404);
    }

    @Test
    void refusedLaunchesLeaveNoJobInstanceBehind() throws Exception {
        copyFixtureToInbox(CLEAN_FIXTURE, "api_refused.csv");

        MvcTestResult outside = ingest("../api_refused.csv", OPERATOR);
        assertThat(outside).hasStatus(422).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(outside).bodyJson().hasPathSatisfying("$.errorCode", c -> c.assertThat().isEqualTo("FILE_OUTSIDE_INBOX"));
        assertThat(ingest("/etc/hostname", OPERATOR)).hasStatus(422);

        MvcTestResult missing = ingest("does_not_exist.csv", OPERATOR);
        assertThat(missing).hasStatus(422);
        assertThat(missing).bodyJson()
                .hasPathSatisfying("$.errorCode", c -> c.assertThat().isEqualTo("FILE_NOT_READABLE"))
                .hasPathSatisfying("$.detail", d -> d.assertThat().asString().doesNotContain(properties.folders().inbox().toString()));

        MvcTestResult blank = ingest("  ", OPERATOR);
        assertThat(blank).hasStatus(400);
        assertThat(blank).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("filePath");

        assertThat(mvc.post().uri("/api/v1/jobs/reconcile/424242").with(as(OPERATOR)).exchange()).hasStatus(404);
        assertThat(mvc.post().uri("/api/v1/jobs/reconcile/424242?gridSize=0").with(as(OPERATOR)).exchange()).hasStatus(400);

        jdbcTemplate.update("""
                insert into statement_file (file_name, checksum_sha256, status, rejection_reason, received_at)
                values ('rejected.csv', ?, 'REJECTED', 'trailer count mismatch', now())
                """, "e".repeat(64));
        long rejectedId = jdbcTemplate.queryForObject("select id from statement_file", Long.class);
        MvcTestResult rejected = mvc.post().uri("/api/v1/jobs/reconcile/{id}", rejectedId).with(as(OPERATOR)).exchange();
        assertThat(rejected).hasStatus(409);
        assertThat(rejected).bodyJson().extractingPath("$.detail").asString().contains("REJECTED");

        assertThat(jdbcTemplate.queryForObject("select count(*) from batch_job_instance", Integer.class)).isZero();
    }

    @Test
    void finishedExecutionsCannotBeStoppedOrRestartedAndUnknownOnesAre404() throws Exception {
        copyFixtureToInbox(CLEAN_FIXTURE, "api_stop.csv");
        long executionId = executionId(ingest("api_stop.csv", OPERATOR));
        awaitFinished(executionId);

        MvcTestResult stop = mvc.post().uri("/api/v1/jobs/executions/{id}/stop", executionId).with(as(OPERATOR)).exchange();
        assertThat(stop).hasStatus(409).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(stop).bodyJson().extractingPath("$.detail").asString().contains("COMPLETED").contains("cannot be stopped");

        MvcTestResult restart = mvc.post().uri("/api/v1/jobs/executions/{id}/restart", executionId).with(as(OPERATOR)).exchange();
        assertThat(restart).hasStatus(409);
        assertThat(restart).bodyJson().extractingPath("$.detail").asString().contains("only FAILED or STOPPED");

        assertThat(mvc.get().uri("/api/v1/jobs/executions/999999").with(as(VIEWER)).exchange()).hasStatus(404);
        assertThat(mvc.post().uri("/api/v1/jobs/executions/999999/stop").with(as(OPERATOR)).exchange()).hasStatus(404);
        assertThat(mvc.post().uri("/api/v1/jobs/executions/999999/restart").with(as(OPERATOR)).exchange()).hasStatus(404);
        assertThat(jdbcTemplate.queryForObject("select count(*) from batch_job_execution", Integer.class)).isEqualTo(1);
    }

    private long reconcileCleanFixture() throws Exception {
        copyFixtureToInbox(CLEAN_FIXTURE, "api_paging.csv");
        awaitFinished(executionId(ingest("api_paging.csv", OPERATOR)));
        long fileId = jdbcTemplate.queryForObject("select id from statement_file", Long.class);
        awaitFinished(executionId(mvc.post().uri("/api/v1/jobs/reconcile/{id}", fileId).with(as(OPERATOR)).exchange()));
        return jdbcTemplate.queryForObject("select id from recon_run", Long.class);
    }

    private MvcTestResult ingest(String filePath, String user) {
        return mvc.post().uri("/api/v1/jobs/ingest")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filePath\": \"" + filePath + "\"}")
                .exchange();
    }

    private MvcTestResult awaitFinished(long executionId) {
        List<String> finished = List.of("COMPLETED", "FAILED", "STOPPED");
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            MvcTestResult result = mvc.get().uri("/api/v1/jobs/executions/{id}", executionId).with(as(VIEWER)).exchange();
            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.status").asString().isIn(finished);
        });
        return mvc.get().uri("/api/v1/jobs/executions/{id}", executionId).with(as(VIEWER)).exchange();
    }

    private static long executionId(MvcTestResult launched) throws Exception {
        Number id = JsonPath.read(launched.getResponse().getContentAsString(), "$.executionId");
        return id.longValue();
    }

    private void copyFixtureToInbox(String fixture, String targetName) throws Exception {
        Path inbox = properties.folders().inbox();
        Files.createDirectories(inbox);
        Path source = Path.of(getClass().getResource("/fixtures/" + fixture).toURI());
        Files.copy(source, inbox.resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
    }
}
