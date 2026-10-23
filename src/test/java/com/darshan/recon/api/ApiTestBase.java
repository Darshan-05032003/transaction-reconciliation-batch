package com.darshan.recon.api;

import static com.darshan.recon.JobMetadataCleanup.removeAllJobs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.darshan.recon.TestcontainersConfiguration;
import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.security.AppUserRepository;
import com.darshan.recon.security.Role;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonPathValueAssert;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Shared context for the HTTP tests: the real security chain, real users with BCrypt
// passwords, and the same PostgreSQL container for every subclass.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
abstract class ApiTestBase {

    static final String VIEWER = "viewer.vijay";
    static final String OPERATOR = "ops.anita";
    static final String SECOND_OPERATOR = "ops.ravi";
    static final String APPROVER = "lead.sunil";
    static final String OPERATOR_AND_APPROVER = "lead.meera";
    static final String PASSWORD = "test-password";
    static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected ReconProperties properties;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedUsersAndStartFromAnEmptyState() {
        if (users.findByUsername(VIEWER).isEmpty()) {
            String hash = passwordEncoder.encode(PASSWORD);
            OffsetDateTime now = OffsetDateTime.now();
            users.upsert(VIEWER, hash, EnumSet.of(Role.VIEWER), now);
            users.upsert(OPERATOR, hash, EnumSet.of(Role.OPERATOR), now);
            users.upsert(SECOND_OPERATOR, hash, EnumSet.of(Role.OPERATOR), now);
            users.upsert(APPROVER, hash, EnumSet.of(Role.APPROVER), now);
            users.upsert(OPERATOR_AND_APPROVER, hash, Set.of(Role.OPERATOR, Role.APPROVER), now);
        }
        removeAllJobs(new JobRepositoryTestUtils(jobRepository), jdbcTemplate);
        jdbcTemplate.update("delete from recon_break_action");
        jdbcTemplate.update("delete from recon_break");
        jdbcTemplate.update("delete from recon_run");
        jdbcTemplate.update("delete from statement_line");
        jdbcTemplate.update("delete from statement_file");
        jdbcTemplate.update("delete from ledger_transaction");
    }

    static RequestPostProcessor as(String username) {
        return httpBasic(username, PASSWORD);
    }

    // JSON numbers come back as Integer, Long or Double depending on their size; comparing
    // through BigDecimal keeps the assertions about amounts exact.
    static void number(JsonPathValueAssert value, String expected) {
        value.asNumber().satisfies(n -> assertThat(new BigDecimal(n.toString())).isEqualByComparingTo(expected));
    }
}
