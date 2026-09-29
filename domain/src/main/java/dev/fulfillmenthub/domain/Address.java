package dev.fulfillmenthub.domain;

import java.util.Locale;

public record Address(String street, String number, String complement, String district,
        String city, String state, String postalCode, String country, Double latitude, Double longitude) {
    public Address {
        street = required(street, 200);
        number = required(number, 20);
        district = required(district, 100);
        city = required(city, 100);
        state = required(state, 50);
        country = required(country, 2).toUpperCase(Locale.ROOT);
        complement = complement == null || complement.isBlank() ? null : complement.trim();
        postalCode = postalCode == null ? "" : postalCode.replaceAll("[^A-Za-z0-9]", "");
        if (postalCode.length() < 4 || postalCode.length() > 10) {
            throw new DomainException("A valid postal code is required.");
        }
        if ((latitude == null) != (longitude == null)
                || latitude != null && (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)) {
            throw new DomainException("Coordinates must be provided together and be within range.");
        }
    }

    static String required(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new DomainException("A required field is missing or exceeds its maximum length.");
        }
        return value.trim();
    }
}
