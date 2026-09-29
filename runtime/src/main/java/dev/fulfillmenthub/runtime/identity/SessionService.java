package dev.fulfillmenthub.runtime.identity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SessionService {
    public record TokenPair(String accessToken, String tokenType, Instant expiresAt,
            String refreshToken, Instant refreshExpiresAt, UUID sessionId) {}
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final TokenSettings settings;
    private final JwtEncoder encoder;
    private final String decoyHash;
    private final SecureRandom random = new SecureRandom();

    public SessionService(EntityManager em, TransactionTemplate tx, PasswordEncoder passwords, Clock clock, TokenSettings settings) {
        this.em = em; this.tx = tx; this.passwords = passwords; this.clock = clock; this.settings = settings;
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(settings.signingKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        decoyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public TokenPair login(String email, String password) {
        var candidate = tx.execute(status -> em.createQuery("select u from UserRow u where u.email = :email", UserRow.class)
                .setParameter("email", email.trim().toLowerCase(java.util.Locale.ROOT)).getResultStream().findFirst().orElse(null));
        boolean verified = matches(password, candidate == null ? decoyHash : candidate.passwordHash);
        if (candidate == null || !verified || !candidate.active) return null;
        var verifiedVersion = candidate.securityVersion;
        return tx.execute(status -> {
            var user = lockUser(candidate.id);
            if (user == null || !user.active || !user.securityVersion.equals(verifiedVersion)) return null;
            user.lastLoginAt = clock.instant();
            var session = new SessionRow();
            session.id = UUID.randomUUID(); session.userId = user.id; session.securityVersion = user.securityVersion;
            session.expiresAt = clock.instant().truncatedTo(ChronoUnit.MILLIS).plus(settings.sessionDays(), ChronoUnit.DAYS);
            em.persist(session);
            return issue(user, session);
        });
    }

    public TokenPair refresh(String secret) {
        if (secret == null || !secret.matches("[A-Fa-f0-9]{64}")) return null;
        var hash = hash(secret);
        var owner = tx.execute(status -> em.createQuery("select s.userId from SessionRow s, RefreshRow r where r.sessionId=s.id and r.hash=:hash", UUID.class)
                .setParameter("hash", hash).getResultStream().findFirst().orElse(null));
        if (owner == null) return null;
        return tx.execute(status -> {
            var user = lockUser(owner);
            var credential = em.find(RefreshRow.class, hash);
            if (user == null || credential == null) return null;
            var session = em.find(SessionRow.class, credential.sessionId);
            if (!active(user, session)) return null;
            if (credential.usedAt != null) {
                session.revokedAt = clock.instant();
                return null; // Commit revocation. Throwing here would roll it back.
            }
            credential.usedAt = clock.instant();
            return issue(user, session);
        });
    }

    public boolean validate(UUID userId, UUID sessionId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var user = em.find(UserRow.class, userId);
            var session = em.find(SessionRow.class, sessionId);
            return session != null && session.userId.equals(userId) && active(user, session);
        }));
    }

    public boolean revoke(UUID userId, UUID callerSession, UUID target) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var user = lockUser(userId);
            var caller = em.find(SessionRow.class, callerSession);
            if (caller == null || !caller.userId.equals(userId) || !active(user, caller)) return false;
            var session = em.find(SessionRow.class, target);
            if (session != null && session.userId.equals(userId) && session.revokedAt == null) session.revokedAt = clock.instant();
            return true;
        }));
    }

    public boolean invalidateAll(UUID userId, UUID callerSession, String currentPassword, String replacement) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var user = lockUser(userId);
            var caller = em.find(SessionRow.class, callerSession);
            if (caller == null || !caller.userId.equals(userId) || !active(user, caller)) return false;
            if (replacement != null) {
                if (!matches(currentPassword, user.passwordHash)) return false;
                user.passwordHash = passwords.encode(replacement);
            }
            user.securityVersion = UUID.randomUUID();
            return true;
        }));
    }

    private UserRow lockUser(UUID id) {
        var user = em.find(UserRow.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (user != null) em.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        return user;
    }
    private boolean active(UserRow user, SessionRow session) {
        return user != null && session != null && user.active && session.revokedAt == null
                && session.expiresAt.isAfter(clock.instant()) && session.securityVersion.equals(user.securityVersion);
    }
    private boolean matches(String password, String hash) {
        try { return passwords.matches(password, hash); } catch (IllegalArgumentException e) { return false; }
    }
    private TokenPair issue(UserRow user, SessionRow session) {
        var bytes = new byte[32]; random.nextBytes(bytes);
        var secret = HexFormat.of().withUpperCase().formatHex(bytes);
        var credential = new RefreshRow(); credential.hash = hash(secret); credential.sessionId = session.id;
        em.persist(credential);
        var now = clock.instant(); var expires = now.plus(settings.accessMinutes(), ChronoUnit.MINUTES);
        var claims = JwtClaimsSet.builder().issuer(settings.issuer()).audience(List.of(settings.audience()))
                .subject(user.id.toString()).issuedAt(now).notBefore(now).expiresAt(expires)
                .id(UUID.randomUUID().toString()).claim("sid", session.id.toString()).claim("role", List.of(user.roles));
        if (user.customerId != null) claims.claim("customer_id", user.customerId.toString());
        var encoded = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()));
        return new TokenPair(encoded.getTokenValue(), "Bearer", expires, secret, session.expiresAt, session.id);
    }
    public static String hash(String value) {
        try { return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
