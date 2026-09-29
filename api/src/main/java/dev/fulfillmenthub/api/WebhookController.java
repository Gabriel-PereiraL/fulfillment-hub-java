package dev.fulfillmenthub.api;
import dev.fulfillmenthub.runtime.webhook.*;import dev.fulfillmenthub.runtime.messaging.WebhookQueue;import jakarta.servlet.http.*;import java.io.IOException;import org.springframework.http.ResponseEntity;import org.springframework.web.bind.annotation.*;import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.core.JacksonException;
@RestController @RequestMapping("/api/v1/webhooks") public class WebhookController{
 private final WebhookInboxService inbox;private final WebhookSettings settings;private final ObjectProvider<WebhookQueue> queue;public WebhookController(WebhookInboxService inbox,WebhookSettings settings,ObjectProvider<WebhookQueue> queue){this.inbox=inbox;this.settings=settings;this.queue=queue;}
 @PostMapping("/payments")public ResponseEntity<Void> payment(HttpServletRequest request,@RequestHeader(name="X-Signature",required=false)String signature,
  @RequestHeader(name="X-Timestamp",required=false)String timestamp)throws IOException{return receive(request,"payments",settings.paymentKey(),signature,timestamp);}
 @PostMapping("/deliveries")public ResponseEntity<Void> delivery(HttpServletRequest request,@RequestHeader(name="X-Uber-Signature",required=false)String signature,
  @RequestHeader(name="X-Timestamp",required=false)String timestamp)throws IOException{return receive(request,"deliveries",settings.deliveryKey(),signature,timestamp);}
 private ResponseEntity<Void> receive(HttpServletRequest request,String provider,String key,String signature,String timestamp)throws IOException{
  var body=request.getInputStream().readAllBytes();if(inbox.verify(key,body,signature,timestamp,settings.toleranceSeconds())!=WebhookInboxService.Verification.Valid)return ResponseEntity.status(401).build();
  try{var identity=inbox.identify(body);if(identity==null)return ResponseEntity.badRequest().build();var receipt=inbox.store(provider,identity,body,request.getHeader("X-Correlation-Id"));var publisher=queue.getIfAvailable();if(publisher!=null)try{publisher.publish(receipt.id());}catch(RuntimeException unavailable){/* durable DB polling is the fallback */}return ResponseEntity.ok().build();}
  catch(JacksonException malformed){return ResponseEntity.badRequest().build();}}
}
