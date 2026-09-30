package dev.fulfillmenthub.api;

import dev.fulfillmenthub.domain.Address;
import dev.fulfillmenthub.domain.Money;
import dev.fulfillmenthub.runtime.orders.OrderPlacementService;
import dev.fulfillmenthub.runtime.orders.ProductRow;
import dev.fulfillmenthub.runtime.orders.OrderService;
import dev.fulfillmenthub.runtime.idempotency.IdempotencyService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CommerceController {
    public record MoneyDto(BigDecimal amount, String currency) {}
    public record ProductDto(UUID id, String sku, String name, MoneyDto unitPrice, int stockQuantity) {}
    public record ItemRequest(@NotNull UUID productId, @Min(1) @Max(99) int quantity) {}
    public record AddressRequest(@NotBlank @Size(max=200) String street, @NotBlank @Size(max=20) String number,
            @Size(max=100) String complement, @NotBlank @Size(max=100) String district,
            @NotBlank @Size(max=100) String city, @NotBlank @Size(max=50) String state,
            @NotBlank @Size(max=12) String postalCode, @Size(min=2,max=2) String country,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude) {}
    public record PlaceRequest(@NotEmpty @Size(max=50) List<@Valid ItemRequest> items,
            @NotNull @Valid AddressRequest deliveryAddress) {}
    public record PlacedDto(UUID id, long number, MoneyDto subtotal, MoneyDto deliveryFee, MoneyDto total) {}
    public record CancelRequest(@Size(max=200) String note) {}

    private final EntityManager em;
    private final OrderPlacementService placement;
    private final IdempotencyService idempotency;
    private final OrderService orderService;
    private final ObjectMapper json;
    public CommerceController(EntityManager em, OrderPlacementService placement, IdempotencyService idempotency,
            OrderService orderService, ObjectMapper json) {
        this.em=em; this.placement=placement; this.idempotency=idempotency; this.orderService=orderService; this.json=json;
    }

    @GetMapping("/products")
    @Transactional(readOnly=true)
    public List<ProductDto> products() {
        return em.createQuery("select p from ProductRow p where p.active=true order by p.name, p.id", ProductRow.class)
                .getResultList().stream().map(p -> new ProductDto(p.id,p.sku,p.name,new MoneyDto(p.amount,p.currency),p.stockQuantity)).toList();
    }

    @PostMapping("/orders")
    public ResponseEntity<?> place(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name="Idempotency-Key") String key,
            @Valid @RequestBody PlaceRequest request) throws JacksonException {
        if (!key.matches("[\\x21-\\x7E]{1,64}")) throw new IllegalArgumentException("Invalid idempotency key.");
        var customerClaim = jwt.getClaimAsString("customer_id");
        if (customerClaim == null) return ResponseEntity.status(403).build();
        var scope=jwt.getSubject();
        {
            var reservation=idempotency.begin(scope,key,requestHash(request));
            if(reservation.state()==IdempotencyService.State.Mismatch)
                return ResponseEntity.status(422).body(java.util.Map.of("title","Idempotency key was used for another request"));
            if(reservation.state()==IdempotencyService.State.InProgress)
                return ResponseEntity.status(409).body(java.util.Map.of("title","Request is already in progress"));
            if(reservation.state()==IdempotencyService.State.Recovered){
                var recovered=orderService.get(reservation.orderId(),UUID.fromString(customerClaim),false);var location="/api/v1/orders/"+reservation.orderId();
                var serialized=json.writeValueAsString(recovered);idempotency.complete(scope,key,201,serialized,MediaType.APPLICATION_JSON_VALUE,location);
                return ResponseEntity.status(201).header("Idempotent-Replayed","true").header("Location",location).body(recovered);
            }
            if(reservation.state()==IdempotencyService.State.Replay){
                var response=ResponseEntity.status(reservation.status()).header("Idempotent-Replayed","true");
                if(reservation.location()!=null)response.header("Location",reservation.location());
                return response.contentType(MediaType.parseMediaType(reservation.contentType()))
                        .body(reservation.body().getBytes(StandardCharsets.UTF_8));
            }
        }
        var a=request.deliveryAddress();
        var address=new Address(a.street(),a.number(),a.complement(),a.district(),a.city(),a.state(),a.postalCode(),
                a.country()==null?"BR":a.country(),a.latitude(),a.longitude());
        try {
            var result=placement.place(UUID.fromString(customerClaim),address,
                    request.items().stream().map(i -> new OrderPlacementService.Line(i.productId(),i.quantity())).toList(),
                    Money.brl("15.00"),key,scope,key);
            var body=orderService.get(result.id(),UUID.fromString(customerClaim),false);
            var location="/api/v1/orders/"+result.id();
            idempotency.complete(scope,key,201,json.writeValueAsString(body),MediaType.APPLICATION_JSON_VALUE,location);
            return ResponseEntity.created(URI.create(location)).body(body);
        } catch (RuntimeException failure) {
            idempotency.release(scope,key);
            throw failure;
        }
    }
    @GetMapping("/orders/{id}")
    public ResponseEntity<OrderService.View> getOrder(@AuthenticationPrincipal Jwt jwt,@PathVariable("id") UUID id) {
        var body=orderService.get(id,customer(jwt),privileged(jwt));
        return body==null?ResponseEntity.notFound().build():ResponseEntity.ok(body);
    }
    @GetMapping("/orders")
    public OrderService.Page listOrders(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) Integer pageSize,@RequestParam(required=false) UUID customerId) {
        return orderService.list(customer(jwt),privileged(jwt),cursor,pageSize,customerId);
    }
    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<OrderService.View> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable("id") UUID id,
            @RequestBody(required=false) @Valid CancelRequest request) {
        var body=orderService.cancel(id,customer(jwt),UUID.fromString(jwt.getSubject()),privileged(jwt),request==null?null:request.note());
        return body==null?ResponseEntity.notFound().build():ResponseEntity.ok(body);
    }
    private static UUID customer(Jwt jwt){var value=jwt.getClaimAsString("customer_id");return value==null?null:UUID.fromString(value);}
    private static boolean privileged(Jwt jwt){var roles=jwt.getClaimAsStringList("role");return roles!=null&&(roles.contains("Admin")||roles.contains("Operator"));}
    private static MoneyDto money(Money value) { return new MoneyDto(value.amount(),value.currency()); }
    private String requestHash(PlaceRequest request) throws JacksonException {
        try {
            var input="POST /api/v1/orders\n"+json.writeValueAsString(request);
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
}
