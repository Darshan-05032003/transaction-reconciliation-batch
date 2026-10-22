package com.darshan.recon.matching;

import com.darshan.recon.ingest.StatementFileRecord;
import com.darshan.recon.ingest.StatementFileRepository;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersValidator;
import org.springframework.stereotype.Component;

@Component
public class ReconcileJobParametersValidator implements JobParametersValidator {

    private final StatementFileRepository statementFiles;

    public ReconcileJobParametersValidator(StatementFileRepository statementFiles) {
        this.statementFiles = statementFiles;
    }

    @Override
    public void validate(JobParameters parameters) throws InvalidJobParametersException {
        if (parameters == null) {
            throw required(ReconcileJobKeys.STATEMENT_FILE_ID_PARAMETER);
        }
        long statementFileId = requireLong(parameters, ReconcileJobKeys.STATEMENT_FILE_ID_PARAMETER);
        Long gridSize = optionalLong(parameters, ReconcileJobKeys.GRID_SIZE_PARAMETER);
        if (gridSize != null && gridSize < 1) {
            throw new InvalidJobParametersException(
                    "job parameter '" + ReconcileJobKeys.GRID_SIZE_PARAMETER + "' must be at least 1");
        }
        StatementFileRecord file = statementFiles.findById(statementFileId)
                .orElseThrow(() -> new InvalidJobParametersException(
                        "statement_file " + statementFileId + " does not exist"));
        switch (file.status()) {
            case VALIDATED -> {
            }
            case RECONCILED -> requireRerun(parameters, file);
            default -> throw new InvalidJobParametersException("statement_file %d has status %s; only VALIDATED files can be reconciled"
                    .formatted(file.id(), file.status()));
        }
    }

    // A reconciled file is reconciled again only on purpose: the rerun tag makes the launch a
    // new job instance and records that an operator asked for it.
    private static void requireRerun(JobParameters parameters, StatementFileRecord file)
            throws InvalidJobParametersException {
        String rerun = parameters.getString(ReconcileJobKeys.RERUN_PARAMETER);
        if (rerun == null || rerun.isBlank()) {
            throw new InvalidJobParametersException(
                    "statement_file %d is already RECONCILED; pass the '%s' parameter to reconcile it again"
                            .formatted(file.id(), ReconcileJobKeys.RERUN_PARAMETER));
        }
    }

    private static long requireLong(JobParameters parameters, String key) throws InvalidJobParametersException {
        Long value = optionalLong(parameters, key);
        if (value == null) {
            throw required(key);
        }
        return value;
    }

    private static Long optionalLong(JobParameters parameters, String key) throws InvalidJobParametersException {
        JobParameter<?> parameter = parameters.getParameter(key);
        if (parameter == null) {
            return null;
        }
        if (parameter.value() instanceof Long value) {
            return value;
        }
        throw new InvalidJobParametersException("job parameter '" + key + "' must be a long");
    }

    private static InvalidJobParametersException required(String key) {
        return new InvalidJobParametersException("job parameter '" + key + "' is required");
    }
}
