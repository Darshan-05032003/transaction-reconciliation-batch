package com.darshan.recon.api;

import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.common.exception.BusinessRuleViolationException;
import com.darshan.recon.common.exception.ConflictException;
import com.darshan.recon.common.exception.NotFoundException;
import com.darshan.recon.ingest.IngestJobKeys;
import com.darshan.recon.ingest.StatementFileRecord;
import com.darshan.recon.ingest.StatementFileRepository;
import com.darshan.recon.matching.ReconRunRepository;
import com.darshan.recon.matching.ReconcileJobKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

// Validates a request fully before it reaches the JobOperator: Spring Batch 6 creates the
// job instance before it runs the JobParametersValidator, so a launch refused there would
// leave an orphan instance behind.
@Service
public class JobLaunchService {

    public static final String LAUNCHED_BY_PARAMETER = "launchedBy";

    private static final Logger log = LoggerFactory.getLogger(JobLaunchService.class);

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Job ingestJob;
    private final Job reconcileJob;
    private final StatementFileRepository statementFiles;
    private final ReconRunRepository reconRuns;
    private final ReconProperties properties;
    private final Clock clock;

    public JobLaunchService(@Qualifier(ApiJobLaunchConfiguration.API_JOB_OPERATOR) JobOperator jobOperator,
                            JobRepository jobRepository,
                            @Qualifier(IngestJobKeys.JOB_NAME) Job ingestJob,
                            @Qualifier(ReconcileJobKeys.JOB_NAME) Job reconcileJob,
                            StatementFileRepository statementFiles, ReconRunRepository reconRuns,
                            ReconProperties properties, Clock clock) {
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.ingestJob = ingestJob;
        this.reconcileJob = reconcileJob;
        this.statementFiles = statementFiles;
        this.reconRuns = reconRuns;
        this.properties = properties;
        this.clock = clock;
    }

    public LaunchResponse ingest(String filePath, String requestedBy) {
        Path file = resolveInboxFile(filePath);
        JobParameters parameters = new JobParametersBuilder()
                .addString(IngestJobKeys.FILE_PATH_PARAMETER, file.toString())
                .addString(LAUNCHED_BY_PARAMETER, requestedBy, false)
                .toJobParameters();
        return launch(ingestJob, parameters);
    }

    public LaunchResponse reconcile(long statementFileId, Integer gridSize, boolean rerun, String requestedBy) {
        StatementFileRecord file = statementFiles.findById(statementFileId)
                .orElseThrow(() -> new NotFoundException("Statement file", statementFileId));
        JobParametersBuilder parameters = new JobParametersBuilder()
                .addLong(ReconcileJobKeys.STATEMENT_FILE_ID_PARAMETER, statementFileId)
                .addString(LAUNCHED_BY_PARAMETER, requestedBy, false);
        switch (file.status()) {
            case VALIDATED -> {
            }
            case RECONCILED -> {
                if (!rerun) {
                    throw new ConflictException("Statement file %d is already RECONCILED; pass rerun=true to reconcile it again"
                            .formatted(statementFileId));
                }
                parameters.addString(ReconcileJobKeys.RERUN_PARAMETER, requestedBy + "@" + OffsetDateTime.now(clock));
            }
            default -> throw new ConflictException("Statement file %d has status %s; only VALIDATED files can be reconciled"
                    .formatted(statementFileId, file.status()));
        }
        if (reconRuns.hasRunningRun(statementFileId)) {
            throw new ConflictException("Statement file %d has a reconciliation run in progress".formatted(statementFileId));
        }
        if (gridSize != null) {
            parameters.addLong(ReconcileJobKeys.GRID_SIZE_PARAMETER, gridSize.longValue(), false);
        }
        return launch(reconcileJob, parameters.toJobParameters());
    }

    public ExecutionView execution(long executionId) {
        return ExecutionView.of(requireExecution(executionId));
    }

    public ExecutionView stop(long executionId) {
        JobExecution execution = requireExecution(executionId);
        try {
            jobOperator.stop(execution);
        } catch (JobExecutionNotRunningException e) {
            throw new ConflictException("Job execution %d is %s and cannot be stopped"
                    .formatted(executionId, execution.getStatus()));
        }
        log.info("Requested stop of job execution {}", executionId);
        return ExecutionView.of(requireExecution(executionId));
    }

    public LaunchResponse restart(long executionId) {
        JobExecution execution = requireExecution(executionId);
        BatchStatus status = execution.getStatus();
        if (status != BatchStatus.FAILED && status != BatchStatus.STOPPED) {
            throw new ConflictException("Job execution %d is %s; only FAILED or STOPPED executions can be restarted"
                    .formatted(executionId, status));
        }
        try {
            JobExecution restarted = jobOperator.restart(execution);
            log.info("Restarted job execution {} as execution {}", executionId, restarted.getId());
            return LaunchResponse.of(restarted);
        } catch (JobRestartException e) {
            throw new ConflictException("Job execution %d cannot be restarted: %s".formatted(executionId, e.getMessage()));
        }
    }

    private LaunchResponse launch(Job job, JobParameters parameters) {
        try {
            JobExecution execution = jobOperator.start(job, parameters);
            log.info("Launched {} as job execution {}", job.getName(), execution.getId());
            return LaunchResponse.of(execution);
        } catch (JobInstanceAlreadyCompleteException e) {
            throw new ConflictException(job.getName() + " has already completed for these parameters");
        } catch (JobExecutionAlreadyRunningException e) {
            throw new ConflictException(job.getName() + " is already running for these parameters");
        } catch (JobRestartException e) {
            throw new ConflictException(job.getName() + " cannot be started: " + e.getMessage());
        } catch (InvalidJobParametersException e) {
            throw new BusinessRuleViolationException("INVALID_JOB_PARAMETERS", e.getMessage());
        }
    }

    // Batch 6.0.6's JDBC DAO resolves the instance id before its own "no such execution"
    // guard, so an unknown id surfaces as EmptyResultDataAccessException rather than null.
    private JobExecution requireExecution(long executionId) {
        JobExecution execution;
        try {
            execution = jobRepository.getJobExecution(executionId);
        } catch (EmptyResultDataAccessException e) {
            execution = null;
        }
        if (execution == null) {
            throw new NotFoundException("Job execution", executionId);
        }
        return execution;
    }

    // A bare file name is resolved against the inbox; an absolute path is accepted only when
    // it stays inside it, so the API cannot be used to read arbitrary files on the host.
    private Path resolveInboxFile(String filePath) {
        Path inbox = properties.folders().inbox().toAbsolutePath().normalize();
        Path file = inbox.resolve(filePath).toAbsolutePath().normalize();
        if (!file.startsWith(inbox)) {
            throw new BusinessRuleViolationException("FILE_OUTSIDE_INBOX",
                    "Statement files can only be ingested from the inbox folder");
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new BusinessRuleViolationException("FILE_NOT_READABLE",
                    "Statement file does not exist or is not readable: " + file.getFileName());
        }
        return file;
    }
}
