package dev.fulfillmenthub.api;

import dev.fulfillmenthub.runtime.identity.SessionService;
import dev.fulfillmenthub.runtime.identity.TokenSettings;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean JwtDecoder jwtDecoder(TokenSettings settings, SessionService sessions, Clock clock) {
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(settings.signingKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        var timestamp = new JwtTimestampValidator(java.time.Duration.ofSeconds(30));
        timestamp.setClock(clock);
        OAuth2TokenValidator<Jwt> state = jwt -> {
            try {
                if (jwt.getExpiresAt() != null && jwt.getAudience().contains(settings.audience())
                        && sessions.validate(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")))) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (RuntimeException ignored) {
                // No fallback to signature-only validation when state is unavailable.
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid credentials or session.", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamp, new JwtIssuerValidator(settings.issuer()), state));
        return decoder;
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role"); authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter(); converter.setJwtGrantedAuthoritiesConverter(authorities);
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health/live", "/health/ready", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/webhooks/**").permitAll()
                        .requestMatchers("/api/v1/users/**", "/api/v1/admin/**").hasRole("Admin")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, failure) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            HttpProblem.write(response, 401, "Authentication failed");
                        }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            HttpProblem.write(response, 401, "Authentication failed");
                        })
                        .accessDeniedHandler((request, response, failure) -> HttpProblem.write(response, 403, "Forbidden")))
                .build();
    }
}
