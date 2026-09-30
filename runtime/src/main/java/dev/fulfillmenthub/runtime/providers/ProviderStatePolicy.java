package dev.fulfillmenthub.runtime.providers;

import java.time.Instant;
import java.util.Map;

public final class ProviderStatePolicy {
    public enum Decision { Applied, Duplicate, Stale, Conflict }
    private static final Map<String,Integer> PAYMENT=Map.of("Pending",0,"Submitting",0,"Unknown",0,"Authorized",1,"Paid",2,"Failed",2,"Cancelled",2,"Refunded",3);
    private static final Map<String,Integer> DELIVERY=Map.of("Pending",0,"Requested",1,"Pickup",2,"PickupComplete",3,"Dropoff",4,"Delivered",5,"Cancelled",5,"Returned",5);
    private ProviderStatePolicy() {}

    public static Decision payment(String current,String reported,Instant lastEvent,Instant occurredAt){
        if(lastEvent!=null&&occurredAt.isBefore(lastEvent))return Decision.Stale;if(current.equals(reported))return Decision.Duplicate;
        if("Refunded".equals(current)||"Failed".equals(current)||"Cancelled".equals(current))return Decision.Conflict;
        if("Paid".equals(current))return "Refunded".equals(reported)?Decision.Applied:Decision.Conflict;
        return PAYMENT.getOrDefault(reported,-1)>=PAYMENT.getOrDefault(current,0)?Decision.Applied:Decision.Conflict;
    }

    public static Decision delivery(String current,String reported,Instant lastEvent,Instant occurredAt){
        if(lastEvent!=null&&occurredAt.isBefore(lastEvent))return Decision.Stale;if(current.equals(reported))return Decision.Duplicate;
        if(DELIVERY.getOrDefault(current,0)>=5)return Decision.Conflict;
        return DELIVERY.getOrDefault(reported,-1)>DELIVERY.getOrDefault(current,0)?Decision.Applied:Decision.Conflict;
    }
}
