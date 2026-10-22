package com.darshan.recon.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// Users created at startup under the dev profile. Passwords come from environment variables
// and are hashed before they are stored; a blank password means the user is skipped.
@ConfigurationProperties("app.security")
@Validated
public record SeedUsersProperties(List<@Valid SeedUser> seedUsers) {

    public SeedUsersProperties {
        seedUsers = seedUsers == null ? List.of() : List.copyOf(seedUsers);
    }

    public record SeedUser(@NotBlank String username, String password, @NotEmpty Set<Role> roles) {

        public boolean hasPassword() {
            return password != null && !password.isBlank();
        }
    }
}
