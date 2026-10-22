package com.darshan.recon.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersValidator;

public class StatementFilePathValidator implements JobParametersValidator {

    @Override
    public void validate(JobParameters parameters) throws InvalidJobParametersException {
        String filePath = parameters == null ? null : parameters.getString(IngestJobKeys.FILE_PATH_PARAMETER);
        if (filePath == null || filePath.isBlank()) {
            throw new InvalidJobParametersException(
                    "job parameter '" + IngestJobKeys.FILE_PATH_PARAMETER + "' is required");
        }
        Path file = Path.of(filePath);
        if (!Files.isRegularFile(file)) {
            throw new InvalidJobParametersException("statement file does not exist: " + filePath);
        }
        if (!Files.isReadable(file)) {
            throw new InvalidJobParametersException("statement file is not readable: " + filePath);
        }
    }
}
