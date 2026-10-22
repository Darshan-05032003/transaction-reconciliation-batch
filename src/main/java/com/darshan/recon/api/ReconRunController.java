package com.darshan.recon.api;

import com.darshan.recon.common.exception.NotFoundException;
import com.darshan.recon.matching.ReconBreakType;
import com.darshan.recon.matching.ReconRunRepository;
import com.darshan.recon.report.RunSummary;
import com.darshan.recon.report.RunSummaryRepository;
import com.darshan.recon.resolution.BreakStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/recon-runs", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@Tag(name = "Reconciliation runs", description = "Results of reconcileJob: totals and the breaks of a run")
public class ReconRunController {

    static final int MAX_PAGE_SIZE = 200;

    private final RunSummaryRepository summaries;
    private final BreakQueryRepository breaks;
    private final ReconRunRepository reconRuns;

    public ReconRunController(RunSummaryRepository summaries, BreakQueryRepository breaks, ReconRunRepository reconRuns) {
        this.summaries = summaries;
        this.breaks = breaks;
        this.reconRuns = reconRuns;
    }

    @GetMapping("/{reconRunId}/summary")
    @Operation(summary = "Summarise a reconciliation run",
            description = "Matched and break counts, the match rate as a percentage and the breaks per type and status.")
    public RunSummary summary(@PathVariable long reconRunId) {
        return summaries.findByRunId(reconRunId)
                .orElseThrow(() -> new NotFoundException("Reconciliation run", reconRunId));
    }

    @GetMapping("/{reconRunId}/breaks")
    @Operation(summary = "List the breaks of a run",
            description = "Paged, ordered by id, optionally filtered by break type and status. The page size is capped at 200.")
    public PageResponse<BreakListItem> breaks(@PathVariable long reconRunId,
                                              @RequestParam(required = false) ReconBreakType type,
                                              @RequestParam(required = false) BreakStatus status,
                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        if (!reconRuns.exists(reconRunId)) {
            throw new NotFoundException("Reconciliation run", reconRunId);
        }
        return breaks.findByRun(reconRunId, type, status, page, size);
    }
}
