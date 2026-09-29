package dev.fulfillmenthub.runtime.identity;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("fulfillment.security")
public record TokenSettings(@NotBlank @Size(min = 32) String signingKey,
        @NotBlank String issuer, @NotBlank String audience,
        @Min(1) @Max(1440) int accessMinutes, @Min(1) @Max(30) int sessionDays) {}
