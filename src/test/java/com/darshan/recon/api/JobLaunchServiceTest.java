package com.darshan.recon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.common.config.ReconProperties.Folders;
import com.darshan.recon.common.exception.BusinessRuleViolationException;
import com.darshan.recon.common.exception.ConflictException;
import com.darshan.recon.common.exception.NotFoundException;
import com.darshan.recon.ingest.StatementFileRecord;
import com.darshan.recon.ingest.StatementFileRepository;
import com.darshan.recon.ingest.StatementFileStatus;
import com.darshan.recon.matching.ReconRunRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.EmptyResultDataAccessException;

// The branches the HTTP tests cannot reach with fixtures that finish in milliseconds:
// stopping a running execution, restarting a failed one, and how each operator exception
// is translated for the client.
class JobLaunchServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T02:15:00Z"), ZoneOffset.UTC);

    @TempDir
    Path data;

    private final JobOperator operator = mock(JobOperator.class);
    private final JobRepository repository = mock(JobRepository.class);
    private final Job ingestJob = mock(Job.class);
    private final Job reconcileJob = mock(Job.class);
    private final StatementFileRepository statementFiles = mock(StatementFileRepository.class);
    private final ReconRunRepository reconRuns = mock(ReconRunRepository.class);
    private JobLaunchService service;

    @BeforeEach
    void service() throws Exception {
        when(ingestJob.getName()).thenReturn("ingestStatementJob");
        when(reconcileJob.getName()).thenReturn("reconcileJob");
        Files.createDirectories(data.resolve("inbox"));
        Files.writeString(data.resolve("inbox").resolve("statement.csv"), "HDR\n");
        ReconProperties properties = new ReconProperties(new Folders(data.resolve("inbox"), data.resolve("archive"),
                data.resolve("failed"), data.resolve("error"), data.resolve("reports")), 50, 2, 10,
                new BigDecimal("0.00"), 3, "-", new BigDecimal("1000.00"));
        service = new JobLaunchService(operator, repository, ingestJob, reconcileJob, statementFiles, reconRuns,
                properties, CLOCK);
    }

    @Test
    void stopAsksTheOperatorAndReturnsTheRefreshedExecution() throws Exception {
        JobExecution running = execution(5L, BatchStatus.STARTED);
        when(repository.getJobExecution(5L)).thenReturn(running);

        ExecutionView view = service.stop(5L);

        verify(operator).stop(running);
        assertThat(view.executionId()).isEqualTo(5L);
    }

    @Test
    void stoppingAnExecutionThatIsNotRunningIsAConflict() throws Exception {
        JobExecution done = execution(5L, BatchStatus.COMPLETED);
        when(repository.getJobExecution(5L)).thenReturn(done);
        org.mockito.Mockito.doThrow(new JobExecutionNotRunningException("not running")).when(operator).stop(done);

        assertThatThrownBy(() -> service.stop(5L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Job execution 5 is COMPLETED and cannot be stopped");
    }

    @Test
    void restartOfAFailedExecutionReturnsTheNewExecution() throws Exception {
        JobExecution failed = execution(5L, BatchStatus.FAILED);
        JobExecution restarted = execution(6L, BatchStatus.STARTING);
        when(repository.getJobExecution(5L)).thenReturn(failed);
        when(operator.restart(failed)).thenReturn(restarted);

        LaunchResponse response = service.restart(5L);

        assertThat(response.executionId()).isEqualTo(6L);
        assertThat(response.jobName()).isEqualTo("reconcileJob");
        assertThat(response.status()).isEqualTo(BatchStatus.STARTING);
    }

    @Test
    void restartRefusedByTheOperatorIsAConflictWithItsReason() throws Exception {
        JobExecution stopped = execution(5L, BatchStatus.STOPPED);
        when(repository.getJobExecution(5L)).thenReturn(stopped);
        when(operator.restart(stopped)).thenThrow(new JobRestartException("job is not restartable"));

        assertThatThrownBy(() -> service.restart(5L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Job execution 5 cannot be restarted: job is not restartable");
    }

    @Test
    void unknownExecutionIdsAreNotFoundWhetherTheRepositoryReturnsNullOrThrows() {
        when(repository.getJobExecution(8L)).thenReturn(null);
        when(repository.getJobExecution(9L)).thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> service.execution(8L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.execution(9L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void launchExceptionsAreTranslatedForTheClient() throws Exception {
        when(operator.start(same(ingestJob), any()))
                .thenThrow(new JobExecutionAlreadyRunningException("running"))
                .thenThrow(new JobRestartException("no restart"))
                .thenThrow(new InvalidJobParametersException("filePath is required"));

        assertThatThrownBy(() -> service.ingest("statement.csv", "ops"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("ingestStatementJob is already running for these parameters");
        assertThatThrownBy(() -> service.ingest("statement.csv", "ops"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("ingestStatementJob cannot be started: no restart");
        assertThatThrownBy(() -> service.ingest("statement.csv", "ops"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("filePath is required")
                .extracting(e -> ((BusinessRuleViolationException) e).errorCode()).isEqualTo("INVALID_JOB_PARAMETERS");
    }

    @Test
    void aFileWithARunInProgressCannotBeReconciledAgain() {
        when(statementFiles.findById(7L)).thenReturn(Optional.of(new StatementFileRecord(7L, "statement.csv",
                "000123456789", LocalDate.of(2026, 10, 1), StatementFileStatus.VALIDATED)));
        when(reconRuns.hasRunningRun(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.reconcile(7L, null, false, "ops"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Statement file 7 has a reconciliation run in progress");
    }

    @Test
    void executionViewTruncatesLongExitDescriptionsAndHidesBlankOnes() {
        JobExecution failed = execution(5L, BatchStatus.FAILED);
        failed.setExitStatus(ExitStatus.FAILED.addExitDescription("x".repeat(700)));
        JobExecution clean = execution(6L, BatchStatus.COMPLETED);
        clean.setExitStatus(new ExitStatus("COMPLETED", "   "));
        when(repository.getJobExecution(5L)).thenReturn(failed);
        when(repository.getJobExecution(6L)).thenReturn(clean);

        assertThat(service.execution(5L).exitDescription()).hasSize(500);
        assertThat(service.execution(6L).exitDescription()).isNull();
        assertThat(service.execution(6L).durationMs()).isNull();
    }

    private static JobExecution execution(long id, BatchStatus status) {
        JobExecution execution = new JobExecution(id, new JobInstance(1L, "reconcileJob"), new JobParameters());
        execution.setStatus(status);
        if (status == BatchStatus.FAILED || status == BatchStatus.STOPPED) {
            execution.setStartTime(LocalDateTime.of(2026, 10, 23, 2, 0));
            execution.setEndTime(LocalDateTime.of(2026, 10, 23, 2, 1));
        }
        return execution;
    }
}
