package dev.fulfillmenthub.runtime.orders;

import dev.fulfillmenthub.domain.Address;
import dev.fulfillmenthub.domain.DomainException;
import dev.fulfillmenthub.domain.Money;
import dev.fulfillmenthub.domain.Order;
import dev.fulfillmenthub.domain.Product;
import dev.fulfillmenthub.runtime.outbox.OutboxRow;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderPlacementService {
    public record Line(UUID productId, int quantity) {}
    public record Placed(UUID id, long number, Money subtotal, Money deliveryFee, Money total) {}

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderPlacementService(EntityManager em, TransactionTemplate tx, Clock clock) {
        this.em = em; this.tx = tx; this.clock = clock;
    }

    public Placed place(UUID customerId, Address address, List<Line> requestLines,
            Money deliveryFee, String idempotencyKey) {
        if (requestLines == null || requestLines.isEmpty()) throw new DomainException("An order must have at least one item.");
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return tx.execute(status -> placeOnce(customerId, address, requestLines, deliveryFee, idempotencyKey));
            } catch (OptimisticLockingFailureException failure) {
                em.clear();
                if (attempt == 3) throw failure;
            }
        }
        throw new IllegalStateException("Unreachable reservation retry state.");
    }

    private Placed placeOnce(UUID customerId, Address address, List<Line> requestLines,
            Money deliveryFee, String idempotencyKey) {
        var ids = requestLines.stream().map(Line::productId).distinct().sorted().toList();
        if (ids.size() != requestLines.size()) throw new DomainException("An order cannot contain the same product more than once.");
        var rows = em.createQuery("select p from ProductRow p where p.id in :ids order by p.id", ProductRow.class)
                .setParameter("ids", ids).getResultList();
        if (rows.size() != ids.size()) throw new DomainException("One or more products do not exist.");
        var now = clock.instant();
        var domainLines = requestLines.stream().map(line -> {
            var row = rows.stream().filter(item -> item.id.equals(line.productId())).findFirst().orElseThrow();
            var product = new Product(row.id, row.sku, row.name, new Money(row.amount, row.currency), row.stockQuantity, row.createdAt);
            if (!row.active) product.setActive(false, row.updatedAt);
            product.reserve(line.quantity(), now);
            row.stockQuantity = product.stockQuantity(); row.updatedAt = now;
            return new Order.Line(product, line.quantity());
        }).toList();
        var aggregate = new Order(UUID.randomUUID(), customerId, true, address, domainLines, idempotencyKey, now);
        aggregate.setDeliveryFee(deliveryFee, now);
        var row = map(aggregate);
        em.persist(row);
        em.persist(OutboxRow.pending(row.id, now));
        em.flush();
        return new Placed(row.id, row.number, aggregate.subtotal(), aggregate.deliveryFee(), aggregate.total());
    }

    private static OrderRow map(Order source) {
        var row = new OrderRow(); row.id=source.id(); row.customerId=source.customerId(); row.status=source.status().name();
        row.idempotencyKey=source.idempotencyKey(); row.createdAt=source.createdAt(); row.updatedAt=source.updatedAt();
        var a=source.deliveryAddress(); row.street=a.street(); row.addressNumber=a.number(); row.complement=a.complement();
        row.district=a.district(); row.city=a.city(); row.state=a.state(); row.postalCode=a.postalCode(); row.country=a.country();
        row.latitude=a.latitude(); row.longitude=a.longitude(); row.subtotal=source.subtotal().amount();
        row.subtotalCurrency=source.subtotal().currency(); row.deliveryFee=source.deliveryFee().amount();
        row.deliveryFeeCurrency=source.deliveryFee().currency(); row.total=source.total().amount(); row.totalCurrency=source.total().currency();
        source.items().forEach(item -> { var child=new OrderItemRow(); child.id=UUID.randomUUID(); child.order=row;
            child.productId=item.productId(); child.sku=item.sku(); child.productName=item.productName();
            child.amount=item.unitPrice().amount(); child.currency=item.unitPrice().currency(); child.quantity=item.quantity(); row.items.add(child); });
        source.statusHistory().stream().sorted(Comparator.comparing(Order.StatusChange::at)).forEach(change -> {
            var child=new OrderStatusRow(); child.id=UUID.randomUUID(); child.order=row; child.from=change.from().name();
            child.to=change.to().name(); child.at=change.at(); child.reason=change.reason(); child.actorUserId=change.actorUserId(); row.history.add(child); });
        return row;
    }
}
