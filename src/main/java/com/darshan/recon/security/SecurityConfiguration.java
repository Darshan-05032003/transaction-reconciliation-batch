package com.darshan.recon.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

// Stateless HTTP Basic for an internal operations API: every request carries credentials,
// no session or cookie is ever issued, so there is no ambient credential for CSRF to ride on.
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    private static final String VIEWER = Role.VIEWER.name();
    private static final String OPERATOR = Role.OPERATOR.name();
    private static final String APPROVER = Role.APPROVER.name();

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                      ProblemDetailAuthenticationHandler problemHandler) throws Exception {
        http
                .csrf(CsrfConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .httpBasic(basic -> basic
                        .realmName(ProblemDetailAuthenticationHandler.REALM)
                        .authenticationEntryPoint(problemHandler))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole(VIEWER, OPERATOR, APPROVER)
                        .requestMatchers(HttpMethod.POST, "/api/v1/jobs/**").hasRole(OPERATOR)
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/breaks/*/resolve").hasRole(OPERATOR)
                        .requestMatchers(HttpMethod.POST, "/api/v1/breaks/*/approve", "/api/v1/breaks/*/reject")
                            .hasRole(APPROVER)
                        .requestMatchers("/api/**").denyAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
