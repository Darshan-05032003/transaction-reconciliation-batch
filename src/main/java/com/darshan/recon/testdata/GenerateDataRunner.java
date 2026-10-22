package com.darshan.recon.testdata;

import com.darshan.recon.common.config.ReconProperties;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("generate")
class GenerateDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GenerateDataRunner.class);

    private final TestDataGenerator generator;
    private final ReconProperties properties;
    private final Clock clock;

    GenerateDataRunner(TestDataGenerator generator, ReconProperties properties, Clock clock) {
        this.generator = generator;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        GenerationRequest request = GenerationArguments.parse(args, LocalDate.now(clock));
        log.info("Generating {} ledger rows for {} with seed {}", request.rows(), request.date(), request.seed());

        GenerationManifest manifest = generator.generate(request, properties.folders().inbox());

        log.info("Statement {} declares {} records totalling {}; expected matched {}, breaks {}, lines to skip {}",
                manifest.fileName(), manifest.declaredCount(), manifest.declaredTotal(),
                manifest.expectedMatched(), manifest.expectedBreaks(), manifest.expectedSkippedLines());
    }
}
