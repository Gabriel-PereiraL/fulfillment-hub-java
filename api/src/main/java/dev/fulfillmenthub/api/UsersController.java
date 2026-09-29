package dev.fulfillmenthub.api;

import dev.fulfillmenthub.runtime.identity.UserRow;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class UsersController {
    public record UserView(UUID id, String email, boolean active, UUID customerId, List<String> roles,
                           Instant createdAt, Instant lastLoginAt) {}
    private final EntityManager em;
    public UsersController(EntityManager em) { this.em = em; }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<UserView> get(@PathVariable("id") UUID id) {
        var user = em.find(UserRow.class, id);
        return user == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(new UserView(
                user.id, user.email, user.active, user.customerId, List.of(user.roles), user.createdAt, user.lastLoginAt));
    }
}
