package com.darshan.recon.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.recon")
@Validated
public record ReconProperties(
        @NotNull @Valid Folders folders,
        @Min(1) @Max(10_000) int chunkSize,
        @Min(1) @Max(64) int gridSize,
        @Min(0) int skipLimit,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal amountTolerance,
        @Min(0) @Max(30) int dateToleranceDays,
        @NotBlank String pollCron) {

    public static final String POLLING_DISABLED = "-";

    public record Folders(
            @NotNull Path inbox,
            @NotNull Path archive,
            @NotNull Path failed,
            @NotNull Path error,
            @NotNull Path reports) {
    }

    @AssertTrue(message = "must be a valid Spring cron expression or \"-\" to disable polling")
    public boolean isPollCronValid() {
        return POLLING_DISABLED.equals(pollCron) || CronExpression.isValidExpression(pollCron);
    }
}
