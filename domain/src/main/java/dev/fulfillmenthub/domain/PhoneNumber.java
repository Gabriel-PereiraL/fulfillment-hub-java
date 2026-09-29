package dev.fulfillmenthub.domain;

public record PhoneNumber(String value) {
    public PhoneNumber {
        var digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.length() < 8 || digits.length() > 15) {
            throw new DomainException("A valid E.164 phone number is required.");
        }
        value = "+" + digits;
    }

    public String masked() {
        return value.substring(0, 3) + "*".repeat(value.length() - 7)
                + value.substring(value.length() - 4);
    }
}
