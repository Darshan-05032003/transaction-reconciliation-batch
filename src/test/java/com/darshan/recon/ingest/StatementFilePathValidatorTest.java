package com.darshan.recon.ingest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

class StatementFilePathValidatorTest {

    private final StatementFilePathValidator validator = new StatementFilePathValidator();

    @TempDir
    Path folder;

    @Test
    void acceptsAnExistingReadableFile() throws IOException {
        Path file = Files.writeString(folder.resolve("statement.csv"), "HDR,BANK01,1,2026-10-01\n");

        assertThatCode(() -> validator.validate(withFilePath(file.toString()))).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrBlankFilePath() {
        assertThatThrownBy(() -> validator.validate(new JobParameters()))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("filePath");
        assertThatThrownBy(() -> validator.validate(withFilePath("  ")))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("filePath");
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidJobParametersException.class);
    }

    @Test
    void rejectsAPathThatIsNotAnExistingFile() {
        Path missing = folder.resolve("missing.csv");

        assertThatThrownBy(() -> validator.validate(withFilePath(missing.toString())))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("does not exist");
        assertThatThrownBy(() -> validator.validate(withFilePath(folder.toString())))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("does not exist");
    }

    private static JobParameters withFilePath(String filePath) {
        return new JobParametersBuilder().addString(IngestJobKeys.FILE_PATH_PARAMETER, filePath).toJobParameters();
    }
}
