package com.darshan.recon.api;

import com.darshan.recon.resolution.ResolutionAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveRequest(@NotNull ResolutionAction action, @NotBlank @Size(max = 500) String comment) {
}
