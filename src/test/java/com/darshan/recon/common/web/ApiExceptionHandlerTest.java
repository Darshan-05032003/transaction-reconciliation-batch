package com.darshan.recon.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(ErrorProbeController.class)
@WithMockUser
class ApiExceptionHandlerTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void validationFailureListsFieldErrorsAndEchoesCorrelationId() {
        MvcTestResult result = mvc.post().uri("/probe/validate")
                .with(csrf())
                .header(CorrelationIdFilter.HEADER, "ops-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"action": "", "comment": "this comment is far too long", "breakId": 0}
                        """)
                .exchange();

        assertThat(result).hasStatus(400).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(result).hasHeader(CorrelationIdFilter.HEADER, "ops-123");
        assertThat(result).bodyJson()
                .hasPathSatisfying("$.detail", d -> d.assertThat().isEqualTo("Request validation failed"))
                .hasPathSatisfying("$.instance", i -> i.assertThat().isEqualTo("/probe/validate"))
                .hasPathSatisfying("$.correlationId", c -> c.assertThat().isEqualTo("ops-123"))
                .extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("action", "comment", "breakId");
    }

    @Test
    void malformedJsonIsBadRequestWithCorrelationId() {
        MvcTestResult result = mvc.post().uri("/probe/validate")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json")
                .exchange();

        assertThat(result).hasStatus(400).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.correlationId").asString().isNotBlank();
    }

    @Test
    void notFoundExceptionBecomes404() {
        MvcTestResult result = mvc.get().uri("/probe/not-found").exchange();

        assertThat(result).hasStatus(404).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(result).bodyJson()
                .hasPathSatisfying("$.title", t -> t.assertThat().isEqualTo("Not Found"))
                .hasPathSatisfying("$.detail", d -> d.assertThat().isEqualTo("Statement file 42 not found"))
                .hasPathSatisfying("$.instance", i -> i.assertThat().isEqualTo("/probe/not-found"))
                .extractingPath("$.correlationId").asString().isNotBlank();
    }

    @Test
    void conflictExceptionBecomes409() {
        MvcTestResult result = mvc.get().uri("/probe/conflict").exchange();

        assertThat(result).hasStatus(409).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(result).bodyJson()
                .extractingPath("$.detail").asString().contains("same checksum");
    }

    @Test
    void businessRuleViolationBecomes422() {
        MvcTestResult result = mvc.get().uri("/probe/unprocessable").exchange();

        assertThat(result).hasStatus(422).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(result).bodyJson()
                .extractingPath("$.detail").asString().contains("cannot be approved");
    }

    @Test
    void unexpectedExceptionBecomes500WithoutLeakingInternals() {
        MvcTestResult result = mvc.get().uri("/probe/boom")
                .header(CorrelationIdFilter.HEADER, "ops-500")
                .exchange();

        assertThat(result).hasStatus(500).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(result).bodyText()
                .doesNotContain("hunter2")
                .doesNotContain("10.0.0.7")
                .doesNotContain("IllegalStateException");
        assertThat(result).bodyJson()
                .hasPathSatisfying("$.detail", d -> d.assertThat().isEqualTo("An unexpected error occurred"))
                .hasPathSatisfying("$.correlationId", c -> c.assertThat().isEqualTo("ops-500"));
    }

    @Test
    void unknownPathIsHandledBySpringDefaultsAndStillCarriesCorrelationId() {
        MvcTestResult result = mvc.get().uri("/probe/does-not-exist")
                .header(CorrelationIdFilter.HEADER, "ops-404")
                .exchange();

        assertThat(result).hasStatus(404).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(result).bodyJson()
                .hasPathSatisfying("$.correlationId", c -> c.assertThat().isEqualTo("ops-404"));
    }
}
