package dev.fulfillmenthub.api;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
    private final DataSource dataSource;
    public HealthController(DataSource dataSource) { this.dataSource = dataSource; }
    @GetMapping("/health/live") public String live() { return "Healthy"; }
    @GetMapping("/health/ready") public ResponseEntity<String> ready() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(2) ? ResponseEntity.ok("Healthy") : ResponseEntity.status(503).body("Unhealthy");
        } catch (SQLException e) { return ResponseEntity.status(503).body("Unhealthy"); }
    }
}
