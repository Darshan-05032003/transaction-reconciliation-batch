package com.darshan.recon.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.darshan.recon.TestcontainersConfiguration;
import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.common.config.ReconProperties.Folders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

// The poller is built by hand with temp folders, so the test owns the inbox, the archive
// and the failed folder; the jobs and the database come from the shared test context.
@SpringBootTest
@SpringBatchTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class InboxPollerTest {

    private static final Instant POLL_TIME = Instant.parse("2026-10-23T02:15:00Z");
    private static final String ARCHIVE_DAY = "2026-10-23";

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    @Qualifier("ingestStatementJob")
    private Job ingestJob;

    @Autowired
    @Qualifier("reconcileJob")
    private Job reconcileJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    private ReconProperties properties;

    @TempDir
    Path data;

    private Path inbox;
    private Path archive;
    private Path failed;
    private AdjustableClock clock;
    private InboxPoller poller;

    @BeforeEach
    void startFromAnEmptyState() {
        jobRepositoryTestUtils.removeJobExecutions();
        jdbcTemplate.update("delete from recon_break");
        jdbcTemplate.update("delete from recon_run");
        jdbcTemplate.update("delete from statement_line");
        jdbcTemplate.update("delete from statement_file");
        inbox = data.resolve("inbox");
        archive = data.resolve("archive");
        failed = data.resolve("failed");
        clock = new AdjustableClock(POLL_TIME);
        Folders folders = new Folders(inbox, archive, failed, properties.folders().error(), properties.folders().reports());
        poller = new InboxPoller(folders, jobOperator, ingestJob, reconcileJob, clock);
    }

    @Test
    void settledFileIsIngestedReconciledAndArchivedUnderTheDayOfTheClock() throws Exception {
        Path file = copyFixture("statement_clean.csv", "statement_clean.csv");

        List<FileOutcome> firstPoll = poller.pollOnce();

        assertThat(firstPoll).extracting(FileOutcome::disposition).containsExactly(FileDisposition.PENDING);
        assertThat(file).exists();
        assertThat(jobInstances()).isZero();

        List<FileOutcome> secondPoll = pollLater();

        assertThat(secondPoll).hasSize(1);
        FileOutcome outcome = secondPoll.get(0);
        assertThat(outcome.disposition()).isEqualTo(FileDisposition.ARCHIVED);
        assertThat(outcome.movedTo()).isEqualTo(archive.resolve(ARCHIVE_DAY).resolve("statement_clean.csv"));
        assertThat(outcome.movedTo()).exists();
        assertThat(file).doesNotExist();
        assertThat(inboxEntries()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select status from statement_file", String.class)).isEqualTo("RECONCILED");
        assertThat(jdbcTemplate.queryForObject("select status from recon_run", String.class)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from recon_break", Integer.class)).isEqualTo(5);
        assertThat(parameterValues("receivedAt"))
                .containsExactly(OffsetDateTime.ofInstant(POLL_TIME.plus(Duration.ofMinutes(5)), ZoneOffset.UTC).toString());
        assertThat(parameterValues("launchedBy")).containsExactly("inbox-poller", "inbox-poller");
        assertThat(poller.pollOnce()).isEmpty();
    }

    @Test
    void fileWithWrongControlTotalsGoesToFailedWithAReasonFile() throws Exception {
        copyFixture("statement_wrong_trailer.csv", "statement_wrong_trailer.csv");
        poller.pollOnce();

        List<FileOutcome> outcomes = pollLater();

        FileOutcome outcome = outcomes.get(0);
        assertThat(outcome.disposition()).isEqualTo(FileDisposition.FAILED);
        assertThat(outcome.movedTo()).isEqualTo(failed.resolve("statement_wrong_trailer.csv")).exists();
        assertThat(outcome.reason()).startsWith("REJECTED: trailer declares 3 records totalling 650.00");
        Path reasonFile = failed.resolve("statement_wrong_trailer.csv.reason");
        assertThat(reasonFile).content(StandardCharsets.UTF_8)
                .contains("file: statement_wrong_trailer.csv")
                .contains("outcome: REJECTED")
                .contains("time: 2026-10-23T02:20Z")
                .contains("job: ingestStatementJob execution ")
                .contains("reason: trailer declares 3 records totalling 650.00 but the file contains 3 records totalling 600.00");
        assertThat(inboxEntries()).isEmpty();
        assertThat(archive).isEmptyDirectory();
        assertThat(jdbcTemplate.queryForObject("select status from statement_file", String.class)).isEqualTo("REJECTED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from recon_run", Integer.class)).isZero();
    }

    @Test
    void sameContentAgainEvenUnderTheSameNameIsADuplicateNotARefusedLaunch() throws Exception {
        copyFixture("statement_clean.csv", "statement_clean.csv");
        poller.pollOnce();
        pollLater();

        copyFixture("statement_clean.csv", "statement_clean.csv");
        pollLater();
        List<FileOutcome> resent = pollLater();

        assertThat(resent).hasSize(1);
        assertThat(resent.get(0).disposition()).isEqualTo(FileDisposition.FAILED);
        assertThat(resent.get(0).movedTo()).isEqualTo(failed.resolve("statement_clean.csv"));
        assertThat(resent.get(0).reason()).matches("DUPLICATE: same content as statement_file \\d+");
        assertThat(failed.resolve("statement_clean.csv.reason")).content().contains("outcome: DUPLICATE");

        copyFixture("statement_clean.csv", "statement_clean.csv");
        pollLater();
        List<FileOutcome> resentAgain = pollLater();

        assertThat(resentAgain.get(0).movedTo()).isEqualTo(failed.resolve("statement_clean.1.csv")).exists();
        assertThat(failed.resolve("statement_clean.1.csv.reason")).exists();
        assertThat(jdbcTemplate.queryForObject("select count(*) from statement_file", Integer.class)).isEqualTo(1);
        assertThat(jobInstances()).isEqualTo(4);
    }

    @Test
    void partFilesSidecarsAndFilesStillBeingWrittenAreLeftAlone() throws Exception {
        Files.createDirectories(inbox);
        Path part = Files.writeString(inbox.resolve("statement_incoming.csv.part"), "HDR,BANK01,000123456789,2026-10-01\n");
        Path manifest = Files.writeString(inbox.resolve("statement_clean.manifest.json"), "{}");
        List<String> lines = Files.readAllLines(fixture("statement_clean.csv"));
        Path growing = inbox.resolve("statement_growing.csv");
        Files.write(growing, lines.subList(0, 3), StandardCharsets.UTF_8);

        assertThat(poller.pollOnce()).extracting(FileOutcome::file).containsExactly(growing);
        Files.write(growing, lines.subList(3, lines.size()), StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        List<FileOutcome> afterAppend = pollLater();
        assertThat(afterAppend).extracting(FileOutcome::disposition).containsExactly(FileDisposition.PENDING);
        assertThat(jobInstances()).isZero();

        List<FileOutcome> settled = pollLater();
        assertThat(settled).extracting(FileOutcome::disposition).containsExactly(FileDisposition.ARCHIVED);
        assertThat(archive.resolve(ARCHIVE_DAY).resolve("statement_growing.csv")).exists();
        assertThat(part).exists();
        assertThat(manifest).exists();
        assertThat(inboxEntries()).containsExactlyInAnyOrder(part, manifest);
    }

    @Test
    void emptyInboxIsANoOp() {
        assertThat(poller.pollOnce()).isEmpty();
        assertThat(pollLater()).isEmpty();
        assertThat(inbox).isDirectory();
        assertThat(jobInstances()).isZero();
    }

    @Test
    void unreadableFileStaysInTheInboxUntilItCanBeRead() throws Exception {
        Path file = copyFixture("statement_clean.csv", "statement_locked.csv");
        Files.setPosixFilePermissions(file, EnumSet.noneOf(PosixFilePermission.class));
        assumeTrue(!Files.isReadable(file), "the test user can read a file without permissions");
        poller.pollOnce();

        List<FileOutcome> locked = pollLater();

        assertThat(locked.get(0).disposition()).isEqualTo(FileDisposition.RETRY_LATER);
        assertThat(locked.get(0).reason()).isEqualTo("file is not readable");
        assertThat(file).exists();
        assertThat(jobInstances()).isZero();

        Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        List<FileOutcome> unlocked = pollLater();

        assertThat(unlocked.get(0).disposition()).isEqualTo(FileDisposition.ARCHIVED);
        assertThat(archive.resolve(ARCHIVE_DAY).resolve("statement_locked.csv")).exists();
    }

    private List<FileOutcome> pollLater() {
        clock.advance(Duration.ofMinutes(5));
        return poller.pollOnce();
    }

    private int jobInstances() {
        return jdbcTemplate.queryForObject("select count(*) from batch_job_instance", Integer.class);
    }

    private List<String> parameterValues(String name) {
        return jdbcTemplate.queryForList("""
                select parameter_value from batch_job_execution_params
                where parameter_name = ? order by job_execution_id
                """, String.class, name);
    }

    private List<Path> inboxEntries() throws IOException {
        try (Stream<Path> entries = Files.list(inbox)) {
            return entries.toList();
        }
    }

    private Path copyFixture(String fixtureName, String targetName) throws Exception {
        Files.createDirectories(inbox);
        return Files.copy(fixture(fixtureName), inbox.resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(InboxPollerTest.class.getResource("/fixtures/" + name).toURI());
    }
}
