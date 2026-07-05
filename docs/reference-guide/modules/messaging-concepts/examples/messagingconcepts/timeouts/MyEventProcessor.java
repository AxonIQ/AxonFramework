package messagingconcepts.timeouts;

import org.axonframework.messaging.core.annotation.MessageHandlerTimeout;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

// tag::message-handler-timeout[]
class MyEventProcessor {
    @EventHandler
    @MessageHandlerTimeout(timeoutMs = 10000, warningThresholdMs = 5000, warningIntervalMs = 1000)
    public void handle(Object event) throws InterruptedException
    {
        Thread.sleep(19000);
    }
}
// end::message-handler-timeout[]
