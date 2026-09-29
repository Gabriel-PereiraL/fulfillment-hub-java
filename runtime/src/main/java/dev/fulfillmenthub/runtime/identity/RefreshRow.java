package dev.fulfillmenthub.runtime.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "refresh_credentials")
public class RefreshRow {
    @Id @Column(length = 64) public String hash;
    @Column(nullable = false) public UUID sessionId;
    public Instant usedAt;
    protected RefreshRow() {}
}
