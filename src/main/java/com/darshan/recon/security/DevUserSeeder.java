package com.darshan.recon.security;

import com.darshan.recon.security.SeedUsersProperties.SeedUser;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DevUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    private final SeedUsersProperties properties;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public DevUserSeeder(SeedUsersProperties properties, AppUserRepository users, PasswordEncoder passwordEncoder,
                         Clock clock) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (SeedUser user : properties.seedUsers()) {
            if (!user.hasPassword()) {
                log.warn("Dev user {} has no password configured and was not seeded", user.username());
                continue;
            }
            users.upsert(user.username(), passwordEncoder.encode(user.password()), user.roles(),
                    OffsetDateTime.now(clock));
            log.info("Seeded dev user {} with roles {}", user.username(), user.roles());
        }
    }
}
