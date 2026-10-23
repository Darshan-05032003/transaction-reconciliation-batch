package com.darshan.recon.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.darshan.recon.common.config.ReconProperties.Folders;
import com.darshan.recon.ingest.IngestJobKeys;
import com.darshan.recon.matching.ReconcileJobKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.step.StepExecution;

// What the poller does when a launch or a job goes wrong, with the operator mocked so that
// each failure can be produced on demand; the happy paths run against the real jobs in
// InboxPollerTest.
class InboxPollerFailurePathsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T02:15:00Z"), ZoneOffset.UTC);

    @TempDir
    Path data;

    private final JobOperator operator = mock(JobOperator.class);
    private final Job ingestJob = mock(Job.class);
    private final Job reconcileJob = mock(Job.class);
    private Path inbox;
    private Path failed;
    private Path file;
    private InboxPoller poller;

    @BeforeEach
    void settledFileInTheInbox() throws Exception {
        inbox = data.resolve("inbox");
        failed = data.resolve("failed");
        poller = new InboxPoller(new Folders(inbox, data.resolve("archive"), failed, data.resolve("error"),
                data.resolve("reports")), operator, ingestJob, reconcileJob, CLOCK);
        Files.createDirectories(inbox);
        file = Files.writeString(inbox.resolve("statement.csv"), "HDR,BANK01,000123456789,2026-10-01\nTRL,0,0.00\n");
        assertThat(poller.pollOnce()).extracting(FileOutcome::disposition).containsExactly(FileDisposition.PENDING);
    }

    @Test
    void aLaunchThatThrowsLeavesTheFileInTheInboxForTheNextPoll() throws Exception {
        when(operator.start(same(ingestJob), any())).thenThrow(new JobRestartException("database unavailable"));

        List<FileOutcome> outcomes = poller.pollOnce();

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).disposition()).isEqualTo(FileDisposition.RETRY_LATER);
        assertThat(outcomes.get(0).reason()).isEqualTo("ingest could not be launched: database unavailable");
        assertThat(file).exists();
        assertThat(failed).isEmptyDirectory();

        poller.pollOnce();
        verify(operator, org.mockito.Mockito.times(2)).start(same(ingestJob), any());
    }

    @Test
    void aFailedIngestMovesTheFileToFailedWithTheRestartInstructions() throws Exception {
        JobExecution ingest = execution(11L, IngestJobKeys.JOB_NAME, BatchStatus.FAILED, ExitStatus.FAILED);
        StepExecution load = new StepExecution(IngestJobKeys.LOAD_STEP, ingest);
        load.addFailureException(new IllegalStateException("disk full"));
        ingest.addStepExecutions(List.of(load));
        when(operator.start(same(ingestJob), any())).thenReturn(ingest);

        FileOutcome outcome = poller.pollOnce().get(0);

        assertThat(outcome.disposition()).isEqualTo(FileDisposition.FAILED);
        assertThat(outcome.movedTo()).isEqualTo(failed.resolve("statement.csv")).exists();
        assertThat(outcome.reason()).isEqualTo("FAILED: disk full; to retry, move the file back to the inbox and restart "
                + "it with POST /api/v1/jobs/executions/11/restart");
        assertThat(failed.resolve("statement.csv.reason")).content()
                .contains("outcome: FAILED")
                .contains("job: ingestStatementJob execution 11")
                .contains("statementFileId: none");
        assertThat(file).doesNotExist();
    }

    @Test
    void aReconcileLaunchThatThrowsAfterASuccessfulIngestNamesTheStatementFile() throws Exception {
        when(operator.start(same(ingestJob), any())).thenReturn(completedIngest(11L, 7L));
        when(operator.start(same(reconcileJob), any())).thenThrow(new JobRestartException("pool exhausted"));

        FileOutcome outcome = poller.pollOnce().get(0);

        assertThat(outcome.disposition()).isEqualTo(FileDisposition.FAILED);
        assertThat(outcome.reason()).isEqualTo("FAILED: ingested as statement_file 7 but reconciliation could not be "
                + "launched: pool exhausted; start it with POST /api/v1/jobs/reconcile/7");
        assertThat(failed.resolve("statement.csv.reason")).content().contains("statementFileId: 7");
    }

    @Test
    void aFailedReconcileMovesTheFileToFailedWithItsExecutionId() throws Exception {
        JobExecution reconcile = execution(12L, ReconcileJobKeys.JOB_NAME, BatchStatus.FAILED, ExitStatus.FAILED);
        reconcile.addFailureException(new IllegalStateException("partition 1 failed"));
        when(operator.start(same(ingestJob), any())).thenReturn(completedIngest(11L, 7L));
        when(operator.start(same(reconcileJob), any())).thenReturn(reconcile);

        FileOutcome outcome = poller.pollOnce().get(0);

        assertThat(outcome.disposition()).isEqualTo(FileDisposition.FAILED);
        assertThat(outcome.reason())
                .isEqualTo("FAILED: partition 1 failed; restart it with POST /api/v1/jobs/executions/12/restart");
        assertThat(failed.resolve("statement.csv.reason")).content()
                .contains("job: reconcileJob execution 12")
                .contains("statementFileId: 7");
    }

    @Test
    void aPollStartedWhileAnotherIsRunningReturnsAtOnce() throws Exception {
        when(operator.start(same(ingestJob), any())).thenAnswer(invocation -> {
            assertThat(poller.pollOnce()).isEmpty();
            return completedIngest(11L, 7L);
        });
        when(operator.start(same(reconcileJob), any()))
                .thenReturn(execution(12L, ReconcileJobKeys.JOB_NAME, BatchStatus.COMPLETED, ExitStatus.COMPLETED));

        assertThat(poller.pollOnce()).extracting(FileOutcome::disposition).containsExactly(FileDisposition.ARCHIVED);
    }

    @Test
    void theScheduledEntryPointSwallowsAndLogsAnUnusableInbox() throws IOException {
        Path notAFolder = Files.writeString(data.resolve("inbox-file"), "x");
        InboxPoller broken = new InboxPoller(new Folders(notAFolder, data.resolve("archive"), failed,
                data.resolve("error"), data.resolve("reports")), operator, ingestJob, reconcileJob, CLOCK);

        assertThatCode(broken::poll).doesNotThrowAnyException();
    }

    private static JobExecution completedIngest(long executionId, long statementFileId) {
        JobExecution ingest = execution(executionId, IngestJobKeys.JOB_NAME, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
        ingest.getExecutionContext().putLong(IngestJobKeys.STATEMENT_FILE_ID, statementFileId);
        return ingest;
    }

    private static JobExecution execution(long id, String jobName, BatchStatus status, ExitStatus exitStatus) {
        JobExecution execution = new JobExecution(id, new JobInstance(id, jobName), new JobParameters());
        execution.setStatus(status);
        execution.setExitStatus(exitStatus);
        if (ReconcileJobKeys.JOB_NAME.equals(jobName)) {
            execution.getExecutionContext().putLong(ReconcileJobKeys.RECON_RUN_ID, 3L);
        }
        return execution;
    }
}
