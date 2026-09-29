package dev.fulfillmenthub.api;

import dev.fulfillmenthub.runtime.identity.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class AuthController {
    public record Login(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 256) String password) {}
    public record Refresh(@NotBlank @Size(min = 64, max = 64) String refreshToken) {}
    public record PasswordChange(@NotBlank @Size(max = 256) String currentPassword, @NotBlank @Size(min = 12, max = 256) String newPassword) {}
    public record Me(UUID userId, UUID customerId, List<String> roles) {}
    private final SessionService sessions;
    public AuthController(SessionService sessions) { this.sessions = sessions; }

    @PostMapping("/auth/login") public ResponseEntity<?> login(@Valid @RequestBody Login request) {
        return tokenResponse(sessions.login(request.email(), request.password()));
    }
    @PostMapping("/auth/refresh") public ResponseEntity<?> refresh(@Valid @RequestBody Refresh request) {
        return tokenResponse(sessions.refresh(request.refreshToken()));
    }
    @GetMapping("/me") public Me me(@AuthenticationPrincipal Jwt jwt) {
        var customer = jwt.getClaimAsString("customer_id");
        return new Me(userId(jwt), customer == null ? null : UUID.fromString(customer), jwt.getClaimAsStringList("role"));
    }
    @PostMapping("/auth/logout") public ResponseEntity<?> logout(@AuthenticationPrincipal Jwt jwt) {
        return mutation(sessions.revoke(userId(jwt), sessionId(jwt), sessionId(jwt)));
    }
    @PostMapping("/auth/sessions/{id}/revoke") public ResponseEntity<?> revoke(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        return mutation(sessions.revoke(userId(jwt), sessionId(jwt), id));
    }
    @PostMapping("/auth/sessions/revoke-all") public ResponseEntity<?> revokeAll(@AuthenticationPrincipal Jwt jwt) {
        return mutation(sessions.invalidateAll(userId(jwt), sessionId(jwt), null, null));
    }
    @PostMapping("/auth/change-password") public ResponseEntity<?> password(@Valid @RequestBody PasswordChange request, @AuthenticationPrincipal Jwt jwt) {
        return mutation(sessions.invalidateAll(userId(jwt), sessionId(jwt), request.currentPassword(), request.newPassword()));
    }
    private static UUID userId(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private static UUID sessionId(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("sid")); }
    private static ResponseEntity<?> tokenResponse(SessionService.TokenPair result) {
        return result == null ? invalid() : ResponseEntity.ok(result);
    }
    private static ResponseEntity<?> mutation(boolean result) { return result ? ResponseEntity.noContent().build() : invalid(); }
    private static ResponseEntity<ProblemDetail> invalid() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid credentials or session.");
        problem.setTitle("Authentication failed");
        return ResponseEntity.status(401).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
