package monitoring.metrics.monitorfactory;

// tag::event-processor-monitor-factory[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.monitoring.MessageMonitor;

class AxonConfig {

    public void registerEventProcessorMonitorFactory(MessagingConfigurer configurer) {
        configurer.registerEventMonitor((config, componentType, componentName) -> {
            // Only create a monitor for EventProcessors with a specific name
            if (EventProcessor.class.isAssignableFrom(componentType)
                    && "my-processor".equals(componentName)) {
                return new MyCustomEventMonitor();
            }
            // Return null to skip monitoring for other components
            return null;
        });
    }
}
// end::event-processor-monitor-factory[]

class MyCustomEventMonitor implements MessageMonitor<EventMessage> {

    @Override
    public MessageMonitor.MonitorCallback onMessageIngested(EventMessage message) {
        return NoOpCallback.INSTANCE;
    }
}

enum NoOpCallback implements MessageMonitor.MonitorCallback {
    INSTANCE;

    @Override
    public void reportSuccess() {
    }

    @Override
    public void reportFailure(Throwable cause) {
    }

    @Override
    public void reportIgnored() {
    }
}
