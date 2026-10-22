package com.darshan.recon.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping(path = "/api/v1/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@Tag(name = "Jobs", description = "Launch, inspect, stop and restart the batch jobs")
public class JobController {

    private final JobLaunchService jobs;

    public JobController(JobLaunchService jobs) {
        this.jobs = jobs;
    }

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Ingest a statement file from the inbox",
            description = "Starts ingestStatementJob asynchronously for a file inside the inbox folder and returns "
                    + "the execution id; poll the Location header to follow it.")
    public ResponseEntity<LaunchResponse> ingest(@Valid @RequestBody IngestRequest request, Authentication authentication) {
        return accepted(jobs.ingest(request.filePath(), authentication.getName()));
    }

    @PostMapping("/reconcile/{statementFileId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Reconcile a validated statement file",
            description = "Starts reconcileJob asynchronously. A file that is already RECONCILED needs rerun=true, "
                    + "which creates a new run and keeps the previous one as history.")
    public ResponseEntity<LaunchResponse> reconcile(@PathVariable long statementFileId,
                                                    @RequestParam(required = false) @Min(1) @Max(64) Integer gridSize,
                                                    @RequestParam(defaultValue = "false") boolean rerun,
                                                    Authentication authentication) {
        return accepted(jobs.reconcile(statementFileId, gridSize, rerun, authentication.getName()));
    }

    @GetMapping("/executions/{executionId}")
    @Operation(summary = "Show a job execution",
            description = "Status, exit code, timings and the read, write, filter, skip, commit and rollback "
                    + "counts of every step.")
    public ExecutionView execution(@PathVariable long executionId) {
        return jobs.execution(executionId);
    }

    @PostMapping("/executions/{executionId}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Stop a running job execution",
            description = "Marks the execution STOPPING; the running step stops at its next chunk boundary.")
    public ResponseEntity<ExecutionView> stop(@PathVariable long executionId) {
        return ResponseEntity.accepted().body(jobs.stop(executionId));
    }

    @PostMapping("/executions/{executionId}/restart")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Restart a failed or stopped job execution",
            description = "Creates a new execution of the same job instance that resumes after the last committed "
                    + "chunk and returns its id.")
    public ResponseEntity<LaunchResponse> restart(@PathVariable long executionId) {
        return accepted(jobs.restart(executionId));
    }

    private static ResponseEntity<LaunchResponse> accepted(LaunchResponse launched) {
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/jobs/executions/{id}")
                .build(launched.executionId());
        return ResponseEntity.accepted().location(location).body(launched);
    }
}
