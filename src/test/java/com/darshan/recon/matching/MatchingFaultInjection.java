package com.darshan.recon.matching;

import com.darshan.recon.matching.engine.MatchingEngine;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// Replaces the matching processor of the partition step with one that can be told to fail
// mid-run. Being @Primary, it wins the by-type injection into the step; the production
// configuration is untouched.
@TestConfiguration(proxyBeanMethods = false)
class MatchingFaultInjection {

    @Bean
    MatchingFault matchingFault() {
        return new MatchingFault();
    }

    @Bean
    @StepScope
    @Primary
    MatchingItemProcessor faultInjectingMatchingItemProcessor(
            @Value("#{jobExecutionContext['reconRunId']}") Long reconRunId,
            @Value("#{stepExecutionContext['partitionIndex']}") Integer partitionIndex,
            MatchingEngine matchingEngine, MatchingFault matchingFault) {
        return new FaultInjectingMatchingItemProcessor(matchingEngine, reconRunId, partitionIndex, matchingFault);
    }
}
