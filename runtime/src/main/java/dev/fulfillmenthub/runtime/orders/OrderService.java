package dev.fulfillmenthub.runtime.orders;

import dev.fulfillmenthub.domain.DomainException;
import dev.fulfillmenthub.runtime.outbox.OutboxRow;
import jakarta.persistence.EntityManager;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import dev.fulfillmenthub.runtime.delivery.DeliveryWorkflowService;

@Service
public class OrderService {
 public record MoneyView(java.math.BigDecimal amount,String currency) {}
 public record ItemView(UUID productId,String sku,String productName,MoneyView unitPrice,int quantity,MoneyView lineTotal) {}
 public record ChangeView(String from,String to,Instant at,String reason) {}
 public record AddressView(String street,String number,String complement,String district,String city,String state,String postalCode,String country,Double latitude,Double longitude) {}
 public record View(UUID id,long number,UUID customerId,String status,String cancellationReason,List<ItemView> items,
  AddressView deliveryAddress,MoneyView subtotal,MoneyView deliveryFee,MoneyView total,UUID paymentId,UUID deliveryId,
  List<ChangeView> statusHistory,Instant createdAt,Instant updatedAt) {}
 public record Summary(UUID id,long number,String status,MoneyView total,int itemCount,Instant createdAt) {}
 public record Page(List<Summary> items,String nextCursor) {}

 private final EntityManager em; private final TransactionTemplate tx; private final Clock clock;private final ObjectProvider<DeliveryWorkflowService> deliveries;
 public OrderService(EntityManager em,TransactionTemplate tx,Clock clock,ObjectProvider<DeliveryWorkflowService> deliveries){this.em=em;this.tx=tx;this.clock=clock;this.deliveries=deliveries;}

 public View get(UUID id,UUID customerId,boolean privileged){return tx.execute(s->{var row=em.find(OrderRow.class,id);
  return row==null||!visible(row,customerId,privileged)?null:view(row);});}

 public Page list(UUID customerId,boolean privileged,String cursor,Integer requestedSize,UUID filterCustomer){
  var after=decode(cursor); int size=Math.max(1,Math.min(requestedSize==null?20:requestedSize,100));
  return tx.execute(s->{var jpql=new StringBuilder("select o from OrderRow o where 1=1");
   if(!privileged)jpql.append(" and o.customerId=:customer"); else if(filterCustomer!=null)jpql.append(" and o.customerId=:customer");
   if(after!=null)jpql.append(" and o.number<:after"); jpql.append(" order by o.number desc");
   var query=em.createQuery(jpql.toString(),OrderRow.class).setMaxResults(size+1);
   if(!privileged||filterCustomer!=null)query.setParameter("customer",privileged?filterCustomer:customerId);
   if(after!=null)query.setParameter("after",after); var rows=query.getResultList(); boolean more=rows.size()>size;
   var page=rows.stream().limit(size).map(o->new Summary(o.id,o.number,o.status,new MoneyView(o.total,o.totalCurrency),o.items.size(),o.createdAt)).toList();
   return new Page(page,more?encode(page.getLast().number()):null);});}

 public View cancel(UUID id,UUID customerId,UUID actor,boolean privileged,String note){
  var requiresRemote=tx.execute(s->{var row=em.find(OrderRow.class,id);return row!=null&&"DeliveryRequested".equals(row.status);});var provider=deliveries.getIfAvailable();if(Boolean.TRUE.equals(requiresRemote)&&provider!=null)provider.cancelForOrder(id);
  for(int attempt=1;attempt<=2;attempt++)try{return tx.execute(s->cancelOnce(id,customerId,actor,privileged,note));}
  catch(OptimisticLockingFailureException failure){em.clear();if(attempt==2)throw failure;}
  throw new IllegalStateException("Unreachable cancellation retry state.");
 }
 private View cancelOnce(UUID id,UUID customerId,UUID actor,boolean privileged,String note){var row=em.find(OrderRow.class,id);
  if(row==null||!visible(row,customerId,privileged))return null; if(row.status.equals("Cancelled"))return view(row);
  boolean allowed=privileged?!row.status.equals("Delivered"):
   row.status.equals("Created")||row.status.equals("AwaitingPayment")||row.status.equals("Paid")||row.status.equals("DeliveryRequested");
  if(!allowed)throw new DomainException("Order can no longer be cancelled."); var now=clock.instant(); var previous=row.status;
  for(var item:row.items){var product=em.find(ProductRow.class,item.productId);product.stockQuantity=Math.addExact(product.stockQuantity,item.quantity);product.updatedAt=now;}
  row.status="Cancelled";row.cancellationReason=privileged?"OperatorAction":"CustomerRequest";row.updatedAt=now;
  var change=new OrderStatusRow();change.id=UUID.randomUUID();change.order=row;change.from=previous;change.to="Cancelled";change.at=now;
  change.reason=note==null?row.cancellationReason:note;change.actorUserId=actor;row.history.add(change);
  em.persist(OutboxRow.pending("OrderCancelled",row.id,now));em.flush();return view(row);}

 private static boolean visible(OrderRow row,UUID customerId,boolean privileged){return privileged||customerId!=null&&row.customerId.equals(customerId);}
 private static View view(OrderRow o){return new View(o.id,o.number,o.customerId,o.status,o.cancellationReason,
  o.items.stream().map(i->new ItemView(i.productId,i.sku,i.productName,new MoneyView(i.amount,i.currency),i.quantity,
   new MoneyView(i.amount.multiply(java.math.BigDecimal.valueOf(i.quantity)),i.currency))).toList(),
  new AddressView(o.street,o.addressNumber,o.complement,o.district,o.city,o.state,o.postalCode,o.country,o.latitude,o.longitude),
  new MoneyView(o.subtotal,o.subtotalCurrency),o.deliveryFee==null?null:new MoneyView(o.deliveryFee,o.deliveryFeeCurrency),
  new MoneyView(o.total,o.totalCurrency),o.paymentId,o.deliveryId,o.history.stream().sorted(java.util.Comparator.comparing(h->h.at))
   .map(h->new ChangeView(h.from,h.to,h.at,h.reason)).toList(),o.createdAt,o.updatedAt);}
 private static String encode(long number){return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(8).putLong(number).array());}
 private static Long decode(String cursor){if(cursor==null||cursor.isEmpty())return null;if(cursor.length()>16)throw new IllegalArgumentException("Invalid cursor.");
  try{var bytes=Base64.getUrlDecoder().decode(cursor);if(bytes.length!=8)throw new IllegalArgumentException("Invalid cursor.");return ByteBuffer.wrap(bytes).getLong();}
  catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid cursor.",invalid);}}
}
