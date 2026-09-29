package dev.fulfillmenthub.domain;

import java.util.Locale;

public record EmailAddress(String value) {
    public EmailAddress {
        value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !value.matches("[^\\s@<>]+@[^\\s@<>]+")) {
            throw new DomainException("A valid e-mail address is required.");
        }
    }
}
