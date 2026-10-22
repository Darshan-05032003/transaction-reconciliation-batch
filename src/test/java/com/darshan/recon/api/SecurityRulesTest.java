package com.darshan.recon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.darshan.recon.common.web.CorrelationIdFilter;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class SecurityRulesTest extends ApiTestBase {

    private static final String RESOLVE_BODY = """
            {"action": "RESOLVED", "comment": "checked"}
            """;
    private static final String DECISION_BODY = """
            {"comment": "fine"}
            """;
    private static final String INGEST_BODY = """
            {"filePath": "missing.csv"}
            """;

    // Every endpoint against every role. 404 and 422 mean the request passed authorization
    // and reached the controller, which is the point of those rows.
    static Stream<Arguments> endpointsAndRoles() {
        return Stream.of(
                Arguments.of("GET", "/api/v1/recon-runs/999/summary", VIEWER, 404),
                Arguments.of("GET", "/api/v1/recon-runs/999/summary", OPERATOR, 404),
                Arguments.of("GET", "/api/v1/recon-runs/999/summary", APPROVER, 404),
                Arguments.of("GET", "/api/v1/recon-runs/999/breaks", VIEWER, 404),
                Arguments.of("GET", "/api/v1/jobs/executions/999", VIEWER, 404),
                Arguments.of("GET", "/api/v1/breaks/999", VIEWER, 404),
                Arguments.of("POST", "/api/v1/jobs/ingest", VIEWER, 403),
                Arguments.of("POST", "/api/v1/jobs/ingest", APPROVER, 403),
                Arguments.of("POST", "/api/v1/jobs/ingest", OPERATOR, 422),
                Arguments.of("POST", "/api/v1/jobs/reconcile/999", VIEWER, 403),
                Arguments.of("POST", "/api/v1/jobs/reconcile/999", OPERATOR, 404),
                Arguments.of("POST", "/api/v1/jobs/executions/999/stop", VIEWER, 403),
                Arguments.of("POST", "/api/v1/jobs/executions/999/stop", OPERATOR, 404),
                Arguments.of("POST", "/api/v1/jobs/executions/999/restart", APPROVER, 403),
                Arguments.of("POST", "/api/v1/jobs/executions/999/restart", OPERATOR, 404),
                Arguments.of("PATCH", "/api/v1/breaks/999/resolve", VIEWER, 403),
                Arguments.of("PATCH", "/api/v1/breaks/999/resolve", APPROVER, 403),
                Arguments.of("PATCH", "/api/v1/breaks/999/resolve", OPERATOR, 404),
                Arguments.of("PATCH", "/api/v1/breaks/999/resolve", OPERATOR_AND_APPROVER, 404),
                Arguments.of("POST", "/api/v1/breaks/999/approve", VIEWER, 403),
                Arguments.of("POST", "/api/v1/breaks/999/approve", OPERATOR, 403),
                Arguments.of("POST", "/api/v1/breaks/999/approve", APPROVER, 404),
                Arguments.of("POST", "/api/v1/breaks/999/approve", OPERATOR_AND_APPROVER, 404),
                Arguments.of("POST", "/api/v1/breaks/999/reject", OPERATOR, 403),
                Arguments.of("POST", "/api/v1/breaks/999/reject", APPROVER, 404),
                Arguments.of("DELETE", "/api/v1/breaks/999", APPROVER, 403),
                Arguments.of("GET", "/api/v2/anything", APPROVER, 403));
    }

    @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
    @MethodSource("endpointsAndRoles")
    void authorisesEachEndpointByRole(String method, String path, String user, int expectedStatus) {
        MvcTestResult result = request(method, path).with(as(user)).exchange();

        assertThat(result).hasStatus(expectedStatus);
        if (expectedStatus == 403) {
            assertThat(result).hasContentTypeCompatibleWith(PROBLEM_JSON);
            assertThat(result).bodyJson()
                    .hasPathSatisfying("$.title", t -> t.assertThat().isEqualTo("Forbidden"))
                    .hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo(403))
                    .hasPathSatisfying("$.instance", i -> i.assertThat().isEqualTo(path))
                    .hasPathSatisfying("$.detail", d -> d.assertThat().asString().contains("role"));
        }
    }

    static Stream<Arguments> protectedPaths() {
        return Stream.of(
                Arguments.of("GET", "/api/v1/recon-runs/1/summary"),
                Arguments.of("POST", "/api/v1/jobs/ingest"),
                Arguments.of("PATCH", "/api/v1/breaks/1/resolve"),
                Arguments.of("GET", "/v3/api-docs"),
                Arguments.of("GET", "/swagger-ui/index.html"),
                Arguments.of("GET", "/actuator/info"));
    }

    @ParameterizedTest(name = "{0} {1} anonymous -> 401")
    @MethodSource("protectedPaths")
    void anonymousRequestsGetAProblemDetailWithABasicChallenge(String method, String path) {
        MvcTestResult result = request(method, path)
                .header(CorrelationIdFilter.HEADER, "sec-401")
                .exchange();

        assertThat(result).hasStatus(401).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(result).hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"recon\"");
        assertThat(result).hasHeader(CorrelationIdFilter.HEADER, "sec-401");
        assertThat(result).bodyJson()
                .hasPathSatisfying("$.title", t -> t.assertThat().isEqualTo("Unauthorized"))
                .hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo(401))
                .hasPathSatisfying("$.detail", d -> d.assertThat().isEqualTo("Authentication is required"))
                .hasPathSatisfying("$.instance", i -> i.assertThat().isEqualTo(path))
                .hasPathSatisfying("$.correlationId", c -> c.assertThat().isEqualTo("sec-401"));
    }

    @Test
    void wrongPasswordUnknownUserAndDisabledUserAreAllUnauthorized() {
        assertThat(mvc.get().uri("/api/v1/breaks/1").with(httpBasic(VIEWER, "not-the-password")).exchange())
                .hasStatus(401);
        assertThat(mvc.get().uri("/api/v1/breaks/1").with(httpBasic("nobody", PASSWORD)).exchange())
                .hasStatus(401);

        jdbcTemplate.update("update app_user set enabled = false where username = ?", SECOND_OPERATOR);
        try {
            assertThat(mvc.get().uri("/api/v1/breaks/1").with(as(SECOND_OPERATOR)).exchange()).hasStatus(401);
        } finally {
            jdbcTemplate.update("update app_user set enabled = true where username = ?", SECOND_OPERATOR);
        }
    }

    @Test
    void healthStaysOpenForScripts() {
        assertThat(mvc.get().uri("/actuator/health").exchange()).hasStatusOk();
    }

    @Test
    void apiDocsDescribeBasicAuthAndTheThreeAreas() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").with(as(VIEWER)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson()
                .hasPathSatisfying("$.components.securitySchemes.basicAuth.scheme", s -> s.assertThat().isEqualTo("basic"))
                .hasPathSatisfying("$.paths['/api/v1/jobs/ingest'].post.summary",
                        s -> s.assertThat().asString().contains("Ingest"))
                .hasPathSatisfying("$.paths['/api/v1/breaks/{breakId}/resolve'].patch.tags[0]",
                        t -> t.assertThat().isEqualTo("Break resolution"))
                .hasPathSatisfying("$.paths['/api/v1/recon-runs/{reconRunId}/summary'].get.tags[0]",
                        t -> t.assertThat().isEqualTo("Reconciliation runs"));
    }

    private org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder request(String method, String path) {
        return switch (HttpMethod.valueOf(method).name()) {
            case "GET" -> mvc.get().uri(path);
            case "DELETE" -> mvc.delete().uri(path);
            case "PATCH" -> mvc.patch().uri(path).contentType(MediaType.APPLICATION_JSON).content(RESOLVE_BODY);
            case "POST" -> mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .content(path.contains("/jobs/ingest") ? INGEST_BODY : DECISION_BODY);
            default -> throw new IllegalArgumentException(method);
        };
    }
}
