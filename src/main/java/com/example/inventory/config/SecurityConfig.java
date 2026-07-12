package com.example.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${auth.username:admin}")
    private String username;

    @Value("${auth.password:password}")
    private String password;

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
                .requestMatchers(HttpMethod.GET, "/api/items/**").permitAll()
                .requestMatchers("/h2-console/**", "/actuator/**", "/", "/index.html", "/styles.css", "/app.js").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/items/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/items/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/items/**").hasAnyRole("ADMIN", "OPERATOR")
                .requestMatchers("/api/reports/**").hasAnyRole("ADMIN", "AUDITOR")
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        String encodedPassword = passwordEncoder.encode(password);
        
        UserDetails adminUser = User.withUsername(username)
            .password(encodedPassword)
            .roles("USER", "ADMIN")
            .build();

        UserDetails op1 = User.withUsername("Operator-1")
            .password(encodedPassword)
            .roles("OPERATOR")
            .build();

        UserDetails op2 = User.withUsername("Operator-2")
            .password(encodedPassword)
            .roles("OPERATOR")
            .build();

        UserDetails mgr = User.withUsername("Manager-Admin")
            .password(encodedPassword)
            .roles("USER", "ADMIN")
            .build();

        UserDetails aud = User.withUsername("Auditor-External")
            .password(encodedPassword)
            .roles("AUDITOR")
            .build();

        return new InMemoryUserDetailsManager(adminUser, op1, op2, mgr, aud);
    }

    private static class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken(); // Forces execution of deferred token, writing it to the cookie
            }
            filterChain.doFilter(request, response);
        }
    }
}
