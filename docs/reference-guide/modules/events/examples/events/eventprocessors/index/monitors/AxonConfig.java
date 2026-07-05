package events.eventprocessors.index.monitors;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.monitoring.MessageMonitor;
import org.axonframework.messaging.monitoring.NoOpMessageMonitor;

// tag::register-event-monitor[]
public class AxonConfig {

    public void registerEventMonitor(MessagingConfigurer configurer) {
        configurer.registerEventMonitor(config -> new CustomMessageMonitor());
    }
}
// end::register-event-monitor[]

class CustomMessageMonitor implements MessageMonitor<EventMessage> {

    @Override
    public MessageMonitor.MonitorCallback onMessageIngested(EventMessage message) {
        return NoOpMessageMonitor.INSTANCE.onMessageIngested(message);
    }
}
