package dev.fulfillmenthub.runtime.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "auth_sessions")
public class SessionRow {
    @Id public UUID id;
    @Column(nullable = false) public UUID userId;
    @Column(nullable = false) public UUID securityVersion;
    @Column(nullable = false) public Instant expiresAt;
    public Instant revokedAt;
    protected SessionRow() {}
}
