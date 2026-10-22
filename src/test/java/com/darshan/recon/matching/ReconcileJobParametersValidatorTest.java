package com.darshan.recon.matching;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.darshan.recon.ingest.StatementFileRecord;
import com.darshan.recon.ingest.StatementFileRepository;
import com.darshan.recon.ingest.StatementFileStatus;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

class ReconcileJobParametersValidatorTest {

    private final StatementFileRepository statementFiles = mock(StatementFileRepository.class);
    private final ReconcileJobParametersValidator validator = new ReconcileJobParametersValidator(statementFiles);

    @Test
    void statementFileIdIsRequiredAndMustBeALong() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidJobParametersException.class).hasMessageContaining("statementFileId");
        assertThatThrownBy(() -> validator.validate(new JobParameters()))
                .isInstanceOf(InvalidJobParametersException.class).hasMessageContaining("required");
        assertThatThrownBy(() -> validator.validate(new JobParametersBuilder()
                .addString(ReconcileJobKeys.STATEMENT_FILE_ID_PARAMETER, "7").toJobParameters()))
                .isInstanceOf(InvalidJobParametersException.class).hasMessageContaining("must be a long");
    }

    @Test
    void unknownStatementFileIsRefused() {
        when(statementFiles.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> validator.validate(parameters(99L)))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessage("statement_file 99 does not exist");
    }

    @Test
    void validatedFileIsAccepted() {
        file(StatementFileStatus.VALIDATED);

        assertThatCode(() -> validator.validate(parameters(7L))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(new JobParametersBuilder(parameters(7L))
                .addLong(ReconcileJobKeys.GRID_SIZE_PARAMETER, 4L, false).toJobParameters()))
                .doesNotThrowAnyException();
    }

    @Test
    void gridSizeBelowOneIsRefused() {
        file(StatementFileStatus.VALIDATED);

        assertThatThrownBy(() -> validator.validate(new JobParametersBuilder(parameters(7L))
                .addLong(ReconcileJobKeys.GRID_SIZE_PARAMETER, 0L, false).toJobParameters()))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("gridSize");
    }

    @Test
    void reconciledFileNeedsAnExplicitRerun() {
        file(StatementFileStatus.RECONCILED);

        assertThatThrownBy(() -> validator.validate(parameters(7L)))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessage("statement_file 7 is already RECONCILED; pass the 'rerun' parameter to reconcile it again");
        assertThatThrownBy(() -> validator.validate(new JobParametersBuilder(parameters(7L))
                .addString(ReconcileJobKeys.RERUN_PARAMETER, " ").toJobParameters()))
                .isInstanceOf(InvalidJobParametersException.class);
        assertThatCode(() -> validator.validate(new JobParametersBuilder(parameters(7L))
                .addString(ReconcileJobKeys.RERUN_PARAMETER, "2026-10-23T10:00:00Z").toJobParameters()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = StatementFileStatus.class, names = {"RECEIVED", "REJECTED", "FAILED"})
    void filesThatNeverPassedIngestionAreRefused(StatementFileStatus status) {
        file(status);

        assertThatThrownBy(() -> validator.validate(parameters(7L)))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessage("statement_file 7 has status " + status + "; only VALIDATED files can be reconciled");
    }

    private void file(StatementFileStatus status) {
        when(statementFiles.findById(7L)).thenReturn(Optional.of(new StatementFileRecord(
                7L, "statement.csv", "000123456789", LocalDate.of(2026, 10, 1), status)));
    }

    private static JobParameters parameters(long statementFileId) {
        return new JobParametersBuilder()
                .addLong(ReconcileJobKeys.STATEMENT_FILE_ID_PARAMETER, statementFileId)
                .toJobParameters();
    }
}
