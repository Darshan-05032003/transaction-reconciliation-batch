package com.darshan.recon.common.web;

import com.darshan.recon.common.exception.BusinessRuleViolationException;
import com.darshan.recon.common.exception.ConflictException;
import com.darshan.recon.common.exception.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Test-only controller: gives the error-handling tests one endpoint per failure mode.
@RestController
@RequestMapping("/probe")
class ErrorProbeController {

    record ResolveRequest(@NotBlank String action, @Size(max = 20) String comment, @Min(1) long breakId) {
    }

    @PostMapping("/validate")
    ResolveRequest validate(@Valid @RequestBody ResolveRequest request) {
        return request;
    }

    @GetMapping("/not-found")
    void notFound() {
        throw new NotFoundException("Statement file", 42L);
    }

    @GetMapping("/conflict")
    void conflict() {
        throw new ConflictException("A statement file with the same checksum was already ingested");
    }

    @GetMapping("/unprocessable")
    void unprocessable() {
        throw new BusinessRuleViolationException("SELF_APPROVAL", "A write-off cannot be approved by the user who requested it");
    }

    @GetMapping("/stale")
    void stale() {
        throw new OptimisticLockingFailureException("row version changed");
    }

    @GetMapping("/boom")
    void boom() {
        throw new IllegalStateException("connection to db at 10.0.0.7 as recon/hunter2 refused");
    }
}
