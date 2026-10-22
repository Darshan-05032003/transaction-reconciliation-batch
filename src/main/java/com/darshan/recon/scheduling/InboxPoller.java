package com.darshan.recon.scheduling;

import com.darshan.recon.common.batch.ExecutionFailures;
import com.darshan.recon.common.batch.JobParameterKeys;
import com.darshan.recon.common.config.ReconProperties.Folders;
import com.darshan.recon.common.web.CorrelationIdFilter;
import com.darshan.recon.ingest.IngestJobKeys;
import com.darshan.recon.matching.ReconcileJobKeys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.scheduling.annotation.Scheduled;

// Unattended processing of the inbox: every statement file that has stopped changing is
// ingested and reconciled, then moved out of the inbox so that each file is handled once.
// A file is only picked up after two polls have seen it with the same size and
// modification time, which keeps a file that is still being copied in out of the jobs;
// senders that write a .part file and rename it are never at risk in the first place.
public class InboxPoller {

    static final String LAUNCHED_BY = "inbox-poller";
    static final String REASON_SUFFIX = ".reason";

    private static final Logger log = LoggerFactory.getLogger(InboxPoller.class);
    private static final String STATEMENT_SUFFIX = ".csv";

    private final Folders folders;
    private final JobOperator jobOperator;
    private final Job ingestJob;
    private final Job reconcileJob;
    private final Clock clock;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final Map<Path, FileSnapshot> seen = new HashMap<>();

    public InboxPoller(Folders folders, JobOperator jobOperator, Job ingestJob, Job reconcileJob, Clock clock) {
        this.folders = folders;
        this.jobOperator = jobOperator;
        this.ingestJob = ingestJob;
        this.reconcileJob = reconcileJob;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.recon.poll-cron}")
    public void poll() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.error("Inbox poll failed", e);
        }
    }

    // Never runs twice at the same time within one instance: a second call while a poll is
    // still busy returns at once. The seen-files map is therefore only touched by one thread.
    public List<FileOutcome> pollOnce() {
        if (!polling.compareAndSet(false, true)) {
            log.warn("Inbox poll skipped because the previous poll is still running");
            return List.of();
        }
        try {
            return processInbox();
        } finally {
            polling.set(false);
        }
    }

    private List<FileOutcome> processInbox() {
        createFolders();
        List<Path> candidates = listStatementFiles();
        seen.keySet().retainAll(candidates);
        List<FileOutcome> outcomes = new ArrayList<>();
        for (Path file : candidates) {
            outcomes.add(hasSettled(file) ? process(file) : FileOutcome.pending(file));
        }
        if (!outcomes.isEmpty()) {
            log.info("Inbox poll handled {} file(s): {}", outcomes.size(), summary(outcomes));
        }
        return outcomes;
    }

    private List<Path> listStatementFiles() {
        try (Stream<Path> entries = Files.list(folders.inbox())) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(STATEMENT_SUFFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list the inbox " + folders.inbox(), e);
        }
    }

    private boolean hasSettled(Path file) {
        FileSnapshot current;
        try {
            current = FileSnapshot.of(file);
        } catch (NoSuchFileException e) {
            seen.remove(file);
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the attributes of " + file, e);
        }
        FileSnapshot previous = seen.put(file, current);
        return current.equals(previous);
    }

    private FileOutcome process(Path file) {
        MDC.put(CorrelationIdFilter.MDC_KEY, "poll-" + UUID.randomUUID());
        try {
            FileOutcome outcome = ingestAndReconcile(file);
            if (outcome.disposition() != FileDisposition.RETRY_LATER) {
                seen.remove(file);
            }
            return outcome;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private FileOutcome ingestAndReconcile(Path file) {
        if (!Files.isReadable(file)) {
            log.warn("{} is not readable; it stays in the inbox", file.getFileName());
            return FileOutcome.retryLater(file, "file is not readable");
        }
        JobExecution ingest;
        try {
            ingest = jobOperator.start(ingestJob, ingestParameters(file));
        } catch (Exception e) {
            log.error("Could not launch {} for {}; the file stays in the inbox", IngestJobKeys.JOB_NAME, file.getFileName(), e);
            return FileOutcome.retryLater(file, "ingest could not be launched: " + e.getMessage());
        }
        String exitCode = ingest.getExitStatus().getExitCode();
        if (ingest.getStatus() != BatchStatus.COMPLETED) {
            return failed(file, "FAILED", ingest, null,
                    "%s; to retry, move the file back to the inbox and restart it with POST /api/v1/jobs/executions/%d/restart"
                            .formatted(ExecutionFailures.reason(ingest), ingest.getId()));
        }
        if (IngestJobKeys.EXIT_DUPLICATE.equals(exitCode) || IngestJobKeys.EXIT_REJECTED.equals(exitCode)) {
            return failed(file, exitCode, ingest, null, exitDescription(ingest));
        }
        long statementFileId = ingest.getExecutionContext().getLong(IngestJobKeys.STATEMENT_FILE_ID);

        JobExecution reconcile;
        try {
            reconcile = jobOperator.start(reconcileJob, reconcileParameters(statementFileId));
        } catch (Exception e) {
            log.error("Could not launch {} for statement_file {}", ReconcileJobKeys.JOB_NAME, statementFileId, e);
            return failed(file, "FAILED", ingest, statementFileId,
                    "ingested as statement_file %d but reconciliation could not be launched: %s; start it with "
                            + "POST /api/v1/jobs/reconcile/%d".formatted(statementFileId, e.getMessage(), statementFileId));
        }
        if (reconcile.getStatus() != BatchStatus.COMPLETED) {
            return failed(file, "FAILED", reconcile, statementFileId,
                    "%s; restart it with POST /api/v1/jobs/executions/%d/restart"
                            .formatted(ExecutionFailures.reason(reconcile), reconcile.getId()));
        }
        return archive(file, statementFileId, reconcile);
    }

    private FileOutcome archive(Path file, long statementFileId, JobExecution reconcile) {
        Path dayFolder = folders.archive().resolve(LocalDate.now(clock).toString());
        Path target = moveAtomically(file, dayFolder);
        String reason = "statement_file %d reconciled in run %d".formatted(statementFileId,
                reconcile.getExecutionContext().getLong(ReconcileJobKeys.RECON_RUN_ID));
        log.info("Archived {} to {}: {}", file.getFileName(), target, reason);
        return FileOutcome.archived(file, target, reason);
    }

    private FileOutcome failed(Path file, String outcome, JobExecution execution, Long statementFileId, String detail) {
        Path target = moveAtomically(file, folders.failed());
        String reason = outcome + ": " + detail;
        writeReasonFile(target, outcome, execution, statementFileId, detail);
        log.warn("Moved {} to {}: {}", file.getFileName(), target, reason);
        return FileOutcome.failed(file, target, reason);
    }

    private void writeReasonFile(Path movedFile, String outcome, JobExecution execution, Long statementFileId,
                                 String detail) {
        String content = """
                file: %s
                outcome: %s
                time: %s
                job: %s execution %d
                statementFileId: %s
                reason: %s
                """.formatted(movedFile.getFileName(), outcome, OffsetDateTime.now(clock),
                execution.getJobInstance().getJobName(), execution.getId(),
                statementFileId == null ? "none" : statementFileId, detail);
        Path reasonFile = movedFile.resolveSibling(movedFile.getFileName() + REASON_SUFFIX);
        try {
            Files.writeString(reasonFile, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + reasonFile, e);
        }
    }

    // ATOMIC_MOVE makes the file appear in the target folder complete or not at all; it
    // requires the inbox, archive and failed folders to be on the same file system.
    private static Path moveAtomically(Path file, Path targetFolder) {
        try {
            Files.createDirectories(targetFolder);
            Path target = freeName(targetFolder, file.getFileName().toString());
            return Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot move " + file.getFileName() + " to " + targetFolder, e);
        }
    }

    // A re-sent file keeps its name, and the earlier copy in failed/ must not be overwritten.
    private static Path freeName(Path folder, String fileName) {
        Path candidate = folder.resolve(fileName);
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int attempt = 1; Files.exists(candidate); attempt++) {
            candidate = folder.resolve(base + "." + attempt + extension);
        }
        return candidate;
    }

    // receivedAt is identifying on purpose: a re-sent file under the same name is a new
    // job instance, and whether its content is new is for the checksum step to decide.
    private JobParameters ingestParameters(Path file) {
        return new JobParametersBuilder()
                .addString(IngestJobKeys.FILE_PATH_PARAMETER, file.toAbsolutePath().normalize().toString())
                .addString(IngestJobKeys.RECEIVED_AT_PARAMETER, OffsetDateTime.now(clock).toString())
                .addString(JobParameterKeys.LAUNCHED_BY, LAUNCHED_BY, false)
                .toJobParameters();
    }

    private static JobParameters reconcileParameters(long statementFileId) {
        return new JobParametersBuilder()
                .addLong(ReconcileJobKeys.STATEMENT_FILE_ID_PARAMETER, statementFileId)
                .addString(JobParameterKeys.LAUNCHED_BY, LAUNCHED_BY, false)
                .toJobParameters();
    }

    // The human-readable reason for DUPLICATE and REJECTED sits on the step that decided it.
    private static String exitDescription(JobExecution execution) {
        String exitCode = execution.getExitStatus().getExitCode();
        return execution.getStepExecutions().stream()
                .map(StepExecution::getExitStatus)
                .filter(status -> exitCode.equals(status.getExitCode()))
                .map(ExitStatus::getExitDescription)
                .filter(description -> description != null && !description.isBlank())
                .findFirst()
                .orElse(exitCode);
    }

    private void createFolders() {
        try {
            Files.createDirectories(folders.inbox());
            Files.createDirectories(folders.archive());
            Files.createDirectories(folders.failed());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the inbox folders", e);
        }
    }

    private static String summary(List<FileOutcome> outcomes) {
        Map<FileDisposition, Long> counts = new EnumMap<>(FileDisposition.class);
        outcomes.forEach(outcome -> counts.merge(outcome.disposition(), 1L, Long::sum));
        return counts.toString();
    }

    private record FileSnapshot(long size, FileTime lastModified) {

        static FileSnapshot of(Path file) throws IOException {
            return new FileSnapshot(Files.size(file), Files.getLastModifiedTime(file));
        }
    }
}
