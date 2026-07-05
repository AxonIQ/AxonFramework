package events.eventprocessors.index.interceptors.annotated.surround;

// tag::event-handler-interceptor-surround[]
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.interception.annotation.EventHandlerInterceptor;

// Surround-interceptor: short-circuit events intended for other tenants
public class TenantScopedEventHandler {

    private String tenantId;

    @EventHandlerInterceptor
    MessageStream<?> filterByTenant(
            EventMessage event,
            MessageHandlerInterceptorChain<EventMessage> chain,
            ProcessingContext ctx
    ) {
        if (!tenantId.equals(event.metadata().get("tenantId"))) {
            return MessageStream.empty(); // skip: not our tenant
        }
        return chain.proceed(event, ctx);
    }

    @EventHandler
    void on(OrderPlaced event) { /* ... */ }
}
// end::event-handler-interceptor-surround[]

record OrderPlaced(String orderId) {

}
