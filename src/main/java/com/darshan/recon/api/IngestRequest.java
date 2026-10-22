package com.darshan.recon.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IngestRequest(@NotBlank @Size(max = 1024) String filePath) {
}
