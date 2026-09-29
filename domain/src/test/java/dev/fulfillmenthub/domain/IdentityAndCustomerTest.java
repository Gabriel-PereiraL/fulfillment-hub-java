package dev.fulfillmenthub.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityAndCustomerTest {
    @Test void securityMutationsInvalidateSessionsAndUserKeepsOneRole() {
        var user = new User(UUID.randomUUID(), new EmailAddress(" TEST@example.invalid "), "hash", List.of(User.Role.Customer), Instant.EPOCH);
        assertEquals("test@example.invalid", user.email().value());
        var initial = user.securityVersion();
        user.grantRole(User.Role.Customer);
        assertEquals(initial, user.securityVersion());
        user.grantRole(User.Role.Operator);
        assertNotEquals(initial, user.securityVersion());
        var granted = user.securityVersion();
        user.revokeRole(User.Role.Operator);
        assertNotEquals(granted, user.securityVersion());
        assertThrows(DomainException.class, () -> user.revokeRole(User.Role.Customer));
        var prior = user.securityVersion();
        user.setActive(false);
        assertNotEquals(prior, user.securityVersion());
        prior = user.securityVersion();
        user.setPasswordHash("replacement-hash");
        assertNotEquals(prior, user.securityVersion());
    }

    @Test void defaultAddressMovesOnRemovalAndLimitIsEnforced() {
        var customer = new Customer(UUID.randomUUID(), UUID.randomUUID(), "Test", new EmailAddress("test@example.invalid"), new PhoneNumber("+5581999990000"), Instant.EPOCH);
        var first = customer.addAddress(UUID.randomUUID(), "Home", OrderTest.address(), Instant.EPOCH);
        var second = customer.addAddress(UUID.randomUUID(), "Work", OrderTest.address(), Instant.EPOCH);
        assertTrue(first.isDefault());
        assertFalse(second.isDefault());
        customer.removeAddress(first.id(), Instant.EPOCH);
        assertTrue(customer.addresses().getFirst().isDefault());
        for (int i = 0; i < 4; i++) customer.addAddress(UUID.randomUUID(), "Other", OrderTest.address(), Instant.EPOCH);
        assertThrows(DomainException.class, () -> customer.addAddress(UUID.randomUUID(), "Sixth", OrderTest.address(), Instant.EPOCH));
        assertThrows(DomainException.class, () -> customer.removeAddress(UUID.randomUUID(), Instant.EPOCH));
        assertEquals(1, customer.addresses().stream().filter(Customer.SavedAddress::isDefault).count());
    }
}
