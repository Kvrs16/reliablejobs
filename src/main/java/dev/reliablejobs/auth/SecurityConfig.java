package dev.reliablejobs.auth;

import dev.reliablejobs.web.ApiErrors;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService userDetailsService(UserRepository users) {
    return username ->
        users
            .findByUsername(username)
            .map(
                u ->
                    User.withUsername(u.getUsername())
                        .password(u.getPasswordHash())
                        .roles(u.getRole())
                        .build())
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            a ->
                a.requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/actuator/health")
                    .permitAll()
                    .requestMatchers("/api/admin/**", "/actuator/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/auth/login")
                    .successHandler((req, res, auth) -> res.setStatus(204))
                    .failureHandler(
                        (req, res, e) ->
                            ApiErrors.write(
                                req, res, 401, "AUTHENTICATION_FAILED", "Invalid credentials")))
        .logout(
            l ->
                l.logoutUrl("/api/auth/logout")
                    .logoutSuccessHandler((req, res, a) -> res.setStatus(204)))
        .requestCache(c -> c.disable())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) ->
                            ApiErrors.write(
                                req, res, 401, "UNAUTHENTICATED", "Authentication required"))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            ApiErrors.write(
                                req,
                                res,
                                403,
                                "FORBIDDEN",
                                "Access denied or CSRF token invalid")));
    return http.build();
  }
}
