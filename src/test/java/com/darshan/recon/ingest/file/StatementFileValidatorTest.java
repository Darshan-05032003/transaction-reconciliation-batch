package com.darshan.recon.ingest.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.ingest.file.FileValidation.Accepted;
import com.darshan.recon.ingest.file.FileValidation.Rejected;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatementFileValidatorTest {

    private final StatementFileValidator validator = new StatementFileValidator();

    @TempDir
    Path folder;

    @Test
    void acceptsACleanFileAndReturnsHeaderAndTrailer() throws Exception {
        FileValidation result = validator.validate(fixture("statement_clean.csv"));

        assertThat(result).isInstanceOf(Accepted.class);
        Accepted accepted = (Accepted) result;
        assertThat(accepted.header()).isEqualTo(new StatementHeader("BANK01", "000123456789", LocalDate.of(2026, 10, 1)));
        assertThat(accepted.trailer().recordCount()).isEqualTo(5);
        assertThat(accepted.trailer().totalAmount()).isEqualByComparingTo("20915.74");
    }

    @Test
    void acceptsAFileWhoseMalformedLinesFollowTheControlTotalRule() throws Exception {
        FileValidation result = validator.validate(fixture("statement_bad_lines.csv"));

        assertThat(result).isInstanceOf(Accepted.class);
        assertThat(((Accepted) result).trailer().recordCount()).isEqualTo(7);
    }

    @Test
    void rejectsAFileWhoseTrailerTotalDoesNotMatchTheLines() throws Exception {
        FileValidation result = validator.validate(fixture("statement_wrong_trailer.csv"));

        assertThat(result).isInstanceOf(Rejected.class);
        assertThat(((Rejected) result).reason())
                .isEqualTo("trailer declares 3 records totalling 650.00 but the file contains 3 records totalling 600.00");
    }

    @Test
    void rejectsAFileWhoseTrailerCountDoesNotMatchTheLines() throws IOException {
        Path file = write("""
                HDR,BANK01,000123456789,2026-10-01
                DTL,REF-1,2026-10-01,100.00,INR,NEFT/One
                DTL,REF-2,2026-10-01,200.00,INR,NEFT/Two
                TRL,3,300.00
                """);

        assertThat(validator.validate(file))
                .isEqualTo(new Rejected("trailer declares 3 records totalling 300.00 but the file contains 2 records totalling 300.00"));
    }

    @Test
    void rejectsEmptyFileHeaderOnlyFileAndMissingTrailer() throws IOException {
        assertThat(validator.validate(write(""))).isEqualTo(new Rejected("file is empty"));
        assertThat(validator.validate(write("HDR,BANK01,000123456789,2026-10-01\n")))
                .isEqualTo(new Rejected("file has no trailer"));

        FileValidation truncated = validator.validate(write("""
                HDR,BANK01,000123456789,2026-10-01
                DTL,REF-1,2026-10-01,100.00,INR,NEFT/One
                DTL,REF-2,2026-10-01,200.00,INR,NEFT/Two
                """));
        assertThat(truncated).isInstanceOf(Rejected.class);
        assertThat(((Rejected) truncated).reason()).startsWith("invalid trailer: expected a TRL record");
    }

    @Test
    void rejectsAFileWithAnUnparseableHeader() throws IOException {
        FileValidation result = validator.validate(write("""
                HDR,BANK01,000123456789,01/10/2026
                TRL,0,0.00
                """));

        assertThat(result).isInstanceOf(Rejected.class);
        assertThat(((Rejected) result).reason()).startsWith("invalid header: statementDate is not an ISO date");
    }

    @Test
    void ignoresNonDataLinesBetweenHeaderAndTrailerForTheTotals() throws IOException {
        Path file = write("""
                HDR,BANK01,000123456789,2026-10-01
                DTL,REF-1,2026-10-01,100.00,INR,NEFT/One

                DTL,REF-2,2026-10-01,200.00,INR,NEFT/Two
                TRL,2,300.00
                """);

        assertThat(validator.validate(file)).isInstanceOf(Accepted.class);
    }

    private Path write(String content) throws IOException {
        return Files.writeString(folder.resolve("statement_" + content.hashCode() + ".csv"), content);
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(StatementFileValidatorTest.class.getResource("/fixtures/" + name).toURI());
    }
}
