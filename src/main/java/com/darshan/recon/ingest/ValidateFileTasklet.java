package com.darshan.recon.ingest;

import com.darshan.recon.common.observability.ReconMetrics;
import com.darshan.recon.ingest.file.FileValidation.Accepted;
import com.darshan.recon.ingest.file.FileValidation.Rejected;
import com.darshan.recon.ingest.file.FileValidator;
import com.darshan.recon.ingest.file.StatementRecordFormat;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

public class ValidateFileTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ValidateFileTasklet.class);

    private final Path file;
    private final long statementFileId;
    private final FileValidator validator;
    private final StatementFileRepository statementFiles;
    private final ReconMetrics metrics;

    public ValidateFileTasklet(Path file, long statementFileId, FileValidator validator,
                               StatementFileRepository statementFiles, ReconMetrics metrics) {
        this.file = file;
        this.statementFileId = statementFileId;
        this.validator = validator;
        this.statementFiles = statementFiles;
        this.metrics = metrics;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws IOException {
        switch (validator.validate(file)) {
            case Accepted accepted -> accept(accepted, chunkContext);
            case Rejected rejected -> reject(rejected, contribution);
        }
        return RepeatStatus.FINISHED;
    }

    private void accept(Accepted accepted, ChunkContext chunkContext) {
        statementFiles.recordHeaderAndTrailer(statementFileId, accepted.header(), accepted.trailer());
        ExecutionContext jobContext = chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext();
        jobContext.putString(IngestJobKeys.ACCOUNT_NUMBER, accepted.header().accountNumber());
        jobContext.putInt(IngestJobKeys.DECLARED_COUNT, accepted.trailer().recordCount());
        log.info("statement_file {} passed its control totals: {} records totalling {}", statementFileId,
                accepted.trailer().recordCount(), StatementRecordFormat.formatAmount(accepted.trailer().totalAmount()));
    }

    private void reject(Rejected rejected, StepContribution contribution) {
        statementFiles.markRejected(statementFileId, rejected.reason());
        metrics.fileRejected();
        contribution.setExitStatus(new ExitStatus(IngestJobKeys.EXIT_REJECTED, rejected.reason()));
        log.warn("statement_file {} rejected: {}", statementFileId, rejected.reason());
    }
}
