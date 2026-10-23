package com.darshan.recon.common.batch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.darshan.recon.common.web.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// The prod profile switches the console to Boot's ECS encoder. Logback is configured once
// per JVM, so a Spring context cannot prove the switch inside the shared test JVM; this
// drives the same encoder directly and pins down the contract the MDC design depends on:
// every MDC entry (correlation id, job name, job execution id) becomes a JSON field.
class StructuredLogEncoderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void ecsLinesAreJsonObjectsWithTheMdcEntriesAsFields() {
        LoggerContext context = new LoggerContext();
        context.putObject(Environment.class.getName(), new MockEnvironment()
                .withProperty("logging.structured.ecs.service.name", "transaction-reconciliation-batch"));
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();

        LoggingEvent event = new LoggingEvent(getClass().getName(),
                context.getLogger("com.darshan.recon.ingest.IngestJobListener"), Level.INFO,
                "ingestStatementJob finished file={} status={}", null, new Object[] {"statement.csv", "COMPLETED"});
        event.setMDCPropertyMap(Map.of(
                CorrelationIdFilter.MDC_KEY, "poll-123",
                JobExecutionMdcListener.JOB_NAME, "ingestStatementJob",
                JobExecutionMdcListener.JOB_EXECUTION_ID, "42"));

        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);

        assertThat(line).endsWith("\n");
        Map<String, String> fields = flatten(line);
        assertThat(fields)
                .containsEntry("message", "ingestStatementJob finished file=statement.csv status=COMPLETED")
                .containsEntry("log.level", "INFO")
                .containsEntry("log.logger", "com.darshan.recon.ingest.IngestJobListener")
                .containsEntry("service.name", "transaction-reconciliation-batch")
                .containsEntry("correlationId", "poll-123")
                .containsEntry("jobName", "ingestStatementJob")
                .containsEntry("jobExecutionId", "42")
                .containsKey("@timestamp")
                .containsKey("ecs.version");
    }

    // ECS nests its own fields ({"log": {"level": ...}}); flattening keeps the assertions
    // independent of that shape, while MDC entries stay top-level either way.
    private static Map<String, String> flatten(String line) {
        Map<String, String> fields = new LinkedHashMap<>();
        flattenInto(JSON.readTree(line), "", fields);
        return fields;
    }

    private static void flattenInto(JsonNode node, String prefix, Map<String, String> fields) {
        if (node.isObject()) {
            node.properties().forEach(entry -> flattenInto(entry.getValue(),
                    prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), fields));
        } else {
            fields.put(prefix, node.asString());
        }
    }
}
