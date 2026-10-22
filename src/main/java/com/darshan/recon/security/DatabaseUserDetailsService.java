package com.darshan.recon.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public DatabaseUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(username)
                .map(DatabaseUserDetailsService::toUserDetails)
                .orElseThrow(() -> new UsernameNotFoundException("unknown user"));
    }

    private static UserDetails toUserDetails(AppUser user) {
        return User.withUsername(user.username())
                .password(user.passwordHash())
                .disabled(!user.enabled())
                .authorities(user.roles().stream().map(Role::authority).toArray(String[]::new))
                .build();
    }
}
