package com.darshan.recon.security;

import java.util.Set;

public record AppUser(long id, String username, String passwordHash, boolean enabled, Set<Role> roles) {
}
