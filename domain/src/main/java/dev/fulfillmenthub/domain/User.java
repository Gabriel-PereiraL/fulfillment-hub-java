package dev.fulfillmenthub.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class User {
    public enum Role { Customer, Operator, Admin }
    private final UUID id;
    private final EmailAddress email;
    private final Set<Role> roles;
    private final Instant createdAt;
    private String passwordHash;
    private UUID securityVersion;
    private boolean active = true;
    private Instant lastLoginAt;

    public User(UUID id, EmailAddress email, String hash, Collection<Role> roles, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.email = Objects.requireNonNull(email);
        this.roles = new LinkedHashSet<>(roles);
        if (this.roles.isEmpty() || this.roles.contains(null)) throw new DomainException("A user must have at least one role.");
        this.createdAt = now;
        setPasswordHash(hash);
    }
    public void setPasswordHash(String value) {
        passwordHash = Address.required(value, 512);
        invalidateSessions();
    }
    public void grantRole(Role role) { if (roles.add(Objects.requireNonNull(role))) invalidateSessions(); }
    public void revokeRole(Role role) {
        if (roles.size() == 1 && roles.contains(role)) throw new DomainException("A user must keep at least one role.");
        if (roles.remove(role)) invalidateSessions();
    }
    public void setActive(boolean value) { active = value; invalidateSessions(); }
    public void invalidateSessions() { securityVersion = UUID.randomUUID(); }
    public void recordLogin(Instant now) { lastLoginAt = now; }
    public UUID id() { return id; }
    public EmailAddress email() { return email; }
    public Set<Role> roles() { return Set.copyOf(roles); }
    public String passwordHash() { return passwordHash; }
    public UUID securityVersion() { return securityVersion; }
    public boolean active() { return active; }
    public Instant createdAt() { return createdAt; }
    public Instant lastLoginAt() { return lastLoginAt; }
}
