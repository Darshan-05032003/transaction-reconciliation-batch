package com.darshan.recon.ingest;

import com.darshan.recon.ingest.file.FileChecksum;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

public class ChecksumTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ChecksumTasklet.class);

    private final Path file;
    private final StatementFileRepository statementFiles;
    private final Clock clock;

    public ChecksumTasklet(Path file, StatementFileRepository statementFiles, Clock clock) {
        this.file = file;
        this.statementFiles = statementFiles;
        this.clock = clock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws IOException {
        String checksum = FileChecksum.sha256Hex(file);
        Optional<Long> alreadyIngested = statementFiles.findIdByChecksum(checksum);
        if (alreadyIngested.isPresent()) {
            log.info("File {} has the same content as statement_file {}; nothing to ingest",
                    file.getFileName(), alreadyIngested.get());
            contribution.setExitStatus(new ExitStatus(IngestJobKeys.EXIT_DUPLICATE,
                    "same content as statement_file " + alreadyIngested.get()));
            return RepeatStatus.FINISHED;
        }

        long id = statementFiles.insertReceived(file.getFileName().toString(), checksum, OffsetDateTime.now(clock));
        chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext()
                .putLong(IngestJobKeys.STATEMENT_FILE_ID, id);
        log.info("Registered file {} as statement_file {}", file.getFileName(), id);
        return RepeatStatus.FINISHED;
    }
}
