package events.eventprocessors.index.interceptors.annotated.before;

// tag::event-handler-interceptor-before[]
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.interception.annotation.EventHandlerInterceptor;

// Before-interceptor: runs before every @EventHandler on this component
public class AuditingEventHandler {

    private AuditLog auditLog;

    @EventHandlerInterceptor
    void audit(EventMessage event) {
        auditLog.record(event.type().qualifiedName());
        // chain proceeds automatically after this returns
    }

    @EventHandler
    void on(OrderPlaced event) { /* ... */ }

    @EventHandler
    void on(OrderCancelled event) { /* ... */ }
}
// end::event-handler-interceptor-before[]

class AuditLog {

    void record(Object qualifiedName) {
        // record the access
    }
}

record OrderPlaced(String orderId) {

}

record OrderCancelled(String orderId) {

}
