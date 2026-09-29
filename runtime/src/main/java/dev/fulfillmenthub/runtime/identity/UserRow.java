package dev.fulfillmenthub.runtime.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "users")
public class UserRow {
    @Id public UUID id;
    @Version public long version;
    @Column(nullable = false, unique = true, length = 254) public String email;
    @Column(nullable = false, length = 512) public String passwordHash;
    @Column(nullable = false) public boolean active;
    @Column(nullable = false) public UUID securityVersion;
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(nullable = false, columnDefinition = "text[]") public String[] roles;
    @Column(nullable = false) public Instant createdAt;
    public Instant lastLoginAt;
    public UUID customerId;
    protected UserRow() {}

    public static UserRow create(String email, String hash, String... roles) {
        var user = new UserRow();
        user.id = UUID.randomUUID(); user.email = email; user.passwordHash = hash;
        user.active = true; user.securityVersion = UUID.randomUUID(); user.roles = roles;
        user.createdAt = Instant.now();
        return user;
    }
}
