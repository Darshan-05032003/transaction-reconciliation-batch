package com.darshan.recon.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ReconPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "app.recon.folders.inbox=/var/recon/inbox",
                    "app.recon.folders.archive=/var/recon/archive",
                    "app.recon.folders.failed=/var/recon/failed",
                    "app.recon.folders.error=/var/recon/error",
                    "app.recon.folders.reports=/var/recon/reports",
                    "app.recon.chunk-size=250",
                    "app.recon.grid-size=4",
                    "app.recon.skip-limit=10",
                    "app.recon.amount-tolerance=0.05",
                    "app.recon.date-tolerance-days=2",
                    "app.recon.poll-cron=0 */5 * * * *",
                    "app.recon.write-off-approval-threshold=10000.00");

    @Test
    void bindsNestedFoldersAndNumericSettings() {
        runner.run(context -> {
            ReconProperties properties = context.getBean(ReconProperties.class);

            assertThat(properties.folders().inbox()).isEqualTo(Path.of("/var/recon/inbox"));
            assertThat(properties.folders().reports()).isEqualTo(Path.of("/var/recon/reports"));
            assertThat(properties.chunkSize()).isEqualTo(250);
            assertThat(properties.gridSize()).isEqualTo(4);
            assertThat(properties.skipLimit()).isEqualTo(10);
            assertThat(properties.amountTolerance()).isEqualByComparingTo(new BigDecimal("0.05"));
            assertThat(properties.dateToleranceDays()).isEqualTo(2);
            assertThat(properties.pollCron()).isEqualTo("0 */5 * * * *");
            assertThat(properties.writeOffApprovalThreshold()).isEqualByComparingTo(new BigDecimal("10000.00"));
        });
    }

    @Test
    void acceptsDashToDisablePolling() {
        runner.withPropertyValues("app.recon.poll-cron=-")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsZeroChunkSize() {
        runner.withPropertyValues("app.recon.chunk-size=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("chunkSize"));
    }

    @Test
    void rejectsNegativeAmountTolerance() {
        runner.withPropertyValues("app.recon.amount-tolerance=-0.01")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("amountTolerance"));
    }

    @Test
    void rejectsNegativeWriteOffApprovalThreshold() {
        runner.withPropertyValues("app.recon.write-off-approval-threshold=-1.00")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("writeOffApprovalThreshold"));
    }

    @Test
    void rejectsMalformedCronExpression() {
        runner.withPropertyValues("app.recon.poll-cron=every five minutes")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("pollCronValid"));
    }

    @Test
    void rejectsMissingFolder() {
        runner.withPropertyValues("app.recon.folders.archive=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("folders.archive"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReconProperties.class)
    static class PropertiesConfiguration {
    }
}
