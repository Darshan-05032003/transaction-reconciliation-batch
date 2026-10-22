package com.darshan.recon.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.darshan.recon.security.SeedUsersProperties.SeedUser;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

class DevUserSeederTest {

    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void seedsUsersWithHashedPasswordsAndSkipsThoseWithoutOne() {
        SeedUsersProperties properties = new SeedUsersProperties(List.of(
                new SeedUser("operator", "operator-dev", Set.of(Role.OPERATOR)),
                new SeedUser("viewer", "", Set.of(Role.VIEWER)),
                new SeedUser("lead", "lead-dev", Set.of(Role.OPERATOR, Role.APPROVER))));
        when(encoder.encode("operator-dev")).thenReturn("{bcrypt}hashed-operator");
        when(encoder.encode("lead-dev")).thenReturn("{bcrypt}hashed-lead");

        new DevUserSeeder(properties, users, encoder, clock).run(new DefaultApplicationArguments());

        OffsetDateTime at = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        verify(users).upsert("operator", "{bcrypt}hashed-operator", Set.of(Role.OPERATOR), at);
        verify(users).upsert("lead", "{bcrypt}hashed-lead", Set.of(Role.OPERATOR, Role.APPROVER), at);
        verify(users, never()).upsert(eq("viewer"), any(), any(), any());
        verify(encoder, never()).encode("");
    }

    @Test
    void missingSeedListMeansNothingToDo() {
        new DevUserSeeder(new SeedUsersProperties(null), users, encoder, clock).run(new DefaultApplicationArguments());

        verify(users, never()).upsert(any(), any(), any(), any());
    }
}
