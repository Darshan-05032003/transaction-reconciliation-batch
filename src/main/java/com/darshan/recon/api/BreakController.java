package com.darshan.recon.api;

import com.darshan.recon.resolution.BreakResolutionService;
import com.darshan.recon.resolution.BreakView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/breaks", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Break resolution", description = "Operations workflow: resolve breaks, request, approve and reject write-offs")
public class BreakController {

    private final BreakResolutionService resolution;

    public BreakController(BreakResolutionService resolution) {
        this.resolution = resolution;
    }

    @GetMapping("/{breakId}")
    @Operation(summary = "Show a break with its resolution history")
    public BreakView get(@PathVariable long breakId) {
        return resolution.get(breakId);
    }

    @PatchMapping(path = "/{breakId}/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Resolve or write off an open break",
            description = "RESOLVED closes the break. WRITTEN_OFF closes it directly when the exposure is at or below "
                    + "the approval threshold; above it the break becomes PENDING_APPROVAL until an approver decides.")
    public BreakView resolve(@PathVariable long breakId, @Valid @RequestBody ResolveRequest request,
                             Authentication authentication) {
        return resolution.resolve(breakId, request.action(), request.comment(), authentication.getName());
    }

    @PostMapping(path = "/{breakId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Approve a pending write-off",
            description = "Must be a different user than the one who requested it.")
    public BreakView approve(@PathVariable long breakId, @Valid @RequestBody DecisionRequest request,
                             Authentication authentication) {
        return resolution.approve(breakId, request.comment(), authentication.getName());
    }

    @PostMapping(path = "/{breakId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Reject a pending write-off", description = "The break goes back to OPEN; the request stays in the history.")
    public BreakView reject(@PathVariable long breakId, @Valid @RequestBody DecisionRequest request,
                            Authentication authentication) {
        return resolution.reject(breakId, request.comment(), authentication.getName());
    }
}
