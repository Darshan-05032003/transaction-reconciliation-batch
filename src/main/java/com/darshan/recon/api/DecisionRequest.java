package com.darshan.recon.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DecisionRequest(@NotBlank @Size(max = 500) String comment) {
}
