package monitoring.metrics.custommonitors;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.queryhandling.QueryMessage;

// tag::custom-message-monitors[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.monitoring.MessageMonitor;

class MetricsConfiguration {

    public void configureCustomMonitors(MessagingConfigurer configurer) {
        // Register a monitor for all message types
        configurer.registerMessageMonitor(config -> new MyCustomMessageMonitor());

        // Register monitors for specific message types
        configurer.registerCommandMonitor(config -> new MyCommandMonitor());
        configurer.registerEventMonitor(config -> new MyEventMonitor());
        configurer.registerQueryMonitor(config -> new MyQueryMonitor());
    }
}
// end::custom-message-monitors[]

class MyCustomMessageMonitor implements MessageMonitor<Message> {

    @Override
    public MessageMonitor.MonitorCallback onMessageIngested(Message message) {
        return NoOpCallback.INSTANCE;
    }
}

class MyCommandMonitor implements MessageMonitor<CommandMessage> {

    @Override
    public MessageMonitor.MonitorCallback onMessageIngested(CommandMessage message) {
        return NoOpCallback.INSTANCE;
    }
}

class MyEventMonitor implements MessageMonitor<EventMessage> {

    @Override
    public MessageMonitor.MonitorCallback onMessageIngested(EventMessage message) {
        return NoOpCallback.INSTANCE;
    }
}

class MyQueryMonitor implements MessageMonitor<QueryMessage> {

    @Override
    public MessageMonitor.MonitorCallback onMessageIngested(QueryMessage message) {
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
