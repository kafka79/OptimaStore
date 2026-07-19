package com.inventoryapp.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${auth.username:#{null}}")
    private String username;

    @Value("${auth.password:#{null}}")
    private String password;

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/h2-console/**")
            )
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin())
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/items/export").hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers(HttpMethod.GET, "/api/items/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .requestMatchers("/", "/index.html", "/styles.css", "/app.js").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/items/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/items/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/items/**").hasAnyRole("ADMIN", "OPERATOR")
                .requestMatchers(HttpMethod.PUT, "/api/items/**").hasAnyRole("ADMIN", "OPERATOR")
                .requestMatchers("/api/reports/**").hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/api/audit/**").hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/api/events/**").hasAnyRole("ADMIN", "OPERATOR")
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults());

        if (sslEnabled) {
            http.requiresChannel(channel -> channel.anyRequest().requiresSecure());
        }

        return http.build();
    }

    @Bean
    public org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    }

    @Bean
    public org.springframework.security.provisioning.UserDetailsManager userDetailsService(javax.sql.DataSource dataSource) {
        return new org.springframework.security.provisioning.JdbcUserDetailsManager(dataSource);
    }

    @Bean
    public org.springframework.boot.CommandLineRunner initUsers(
            org.springframework.security.provisioning.UserDetailsManager manager,
            org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        return args -> {
            String actualUser = (username != null && !username.isBlank()) ? username : "admin";

            if (password == null || password.isBlank()) {
                throw new IllegalStateException("Security violation: 'auth.password' property MUST be provided securely (e.g. via environment variables).");
            }

            String encodedPassword = passwordEncoder.encode(password);

            if (!manager.userExists(actualUser)) {
                manager.createUser(User.withUsername(actualUser).password(encodedPassword).roles("USER", "ADMIN").build());
                manager.createUser(User.withUsername("Manager-Admin").password(encodedPassword).roles("USER", "ADMIN").build());

                String op1Pw = java.util.UUID.randomUUID().toString();
                logger.info("Generated password for Operator-1: {}", op1Pw);
                manager.createUser(User.withUsername("Operator-1").password(passwordEncoder.encode(op1Pw)).roles("OPERATOR").build());

                String op2Pw = java.util.UUID.randomUUID().toString();
                logger.info("Generated password for Operator-2: {}", op2Pw);
                manager.createUser(User.withUsername("Operator-2").password(passwordEncoder.encode(op2Pw)).roles("OPERATOR").build());

                String audPw = java.util.UUID.randomUUID().toString();
                logger.info("Generated password for Auditor-External: {}", audPw);
                manager.createUser(User.withUsername("Auditor-External").password(passwordEncoder.encode(audPw)).roles("AUDITOR").build());
            }
        };
    }

    private static class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
