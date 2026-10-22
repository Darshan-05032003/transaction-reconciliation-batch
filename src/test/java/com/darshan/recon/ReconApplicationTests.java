package com.darshan.recon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ReconApplicationTests {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoadsWithJdbcJobRepository() {
        assertThat(jobRepository).isNotNull();

        Integer jobInstances = jdbcTemplate.queryForObject(
                "select count(*) from batch_job_instance", Integer.class);

        assertThat(jobInstances).isZero();
    }
}
