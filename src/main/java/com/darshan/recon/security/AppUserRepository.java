package com.darshan.recon.security;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AppUserRepository {

    private final JdbcTemplate jdbcTemplate;

    public AppUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AppUser> findByUsername(String username) {
        List<AppUser> users = jdbcTemplate.query("""
                select id, username, password_hash, enabled from app_user where username = ?
                """, (rs, rowNum) -> new AppUser(rs.getLong("id"), rs.getString("username"),
                rs.getString("password_hash"), rs.getBoolean("enabled"), EnumSet.noneOf(Role.class)), username);
        if (users.isEmpty()) {
            return Optional.empty();
        }
        AppUser user = users.getFirst();
        List<Role> roles = jdbcTemplate.query("select role from app_user_role where user_id = ?",
                (rs, rowNum) -> Role.valueOf(rs.getString("role")), user.id());
        return Optional.of(new AppUser(user.id(), user.username(), user.passwordHash(), user.enabled(),
                roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles)));
    }

    // Replaces the password hash and the role set of an existing user; used by the dev seeder
    // so that restarting with changed environment variables takes effect.
    @Transactional
    public long upsert(String username, String passwordHash, Set<Role> roles, OffsetDateTime createdAt) {
        Long id = jdbcTemplate.queryForObject("""
                insert into app_user (username, password_hash, enabled, created_at)
                values (?, ?, true, ?)
                on conflict (username) do update set password_hash = excluded.password_hash, enabled = true
                returning id
                """, Long.class, username, passwordHash, createdAt);
        if (id == null) {
            throw new IllegalStateException("insert into app_user returned no id");
        }
        jdbcTemplate.update("delete from app_user_role where user_id = ?", id);
        for (Role role : roles) {
            jdbcTemplate.update("insert into app_user_role (user_id, role) values (?, ?)", id, role.name());
        }
        return id;
    }
}
