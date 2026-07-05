package events.eventprocessors.index.interceptors.component;

import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorsConfigurer;

// tag::intercepted-handling-components[]
public class AxonConfig {

    private final String tenantId = "tenant-a";

    public void configureEventProcessing(MessagingConfigurer configurer) {
        configurer.eventProcessing(eventConfigurer -> eventConfigurer.subscribing(
                this::configureSubscribingProcessor
        ));
    }

    private SubscribingEventProcessorsConfigurer configureSubscribingProcessor(
            SubscribingEventProcessorsConfigurer subscribingConfigurer
    ) {
        return subscribingConfigurer.processor(
                "my-processor",
                config -> config.eventHandlingComponents(this::configureHandlingComponents)
                                .notCustomized()
        );
    }

    private EventHandlingComponentsConfigurer.CompletePhase configureHandlingComponents(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.autodetected("orderHandler", cfg -> new OrderEventHandler())
                                  .autodetected("inventoryHandler", cfg -> new InventoryEventHandler())
                                  .intercepted(cfg -> new AuditLoggingInterceptor())
                                  .intercepted(cfg -> new TenantFilterInterceptor(tenantId));
    }
}
// end::intercepted-handling-components[]

record OrderPlaced(String orderId) {

}

class OrderEventHandler {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(OrderPlaced event) {
        // handle order placement
    }
}

class InventoryEventHandler {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(OrderPlaced event) {
        // adjust inventory
    }
}

class AuditLoggingInterceptor implements MessageHandlerInterceptor<EventMessage> {

    @Override
    public MessageStream<?> interceptOnHandle(EventMessage message,
                                               ProcessingContext context,
                                               MessageHandlerInterceptorChain<EventMessage> chain) {
        // record the event for auditing purposes
        return chain.proceed(message, context);
    }
}

class TenantFilterInterceptor implements MessageHandlerInterceptor<EventMessage> {

    private final String tenantId;

    TenantFilterInterceptor(String tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public MessageStream<?> interceptOnHandle(EventMessage message,
                                               ProcessingContext context,
                                               MessageHandlerInterceptorChain<EventMessage> chain) {
        if (!tenantId.equals(message.metadata().get("tenantId"))) {
            return MessageStream.empty();
        }
        return chain.proceed(message, context);
    }
}
