package messagingconcepts.supportedparameters;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.replay.ReplayStatus;
import org.axonframework.messaging.eventhandling.replay.annotation.ReplayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class EventHandlerReplayContextExample {

    private static final Logger logger = LoggerFactory.getLogger(EventHandlerReplayContextExample.class);

    // tag::event-handler-replay-context[]
    @EventHandler
    public void on(OrderPlacedEvent event,
                   ReplayStatus replayStatus,
                   @ReplayContext String replayReason) {
        if (replayStatus == ReplayStatus.REPLAY) {
            // Special handling during replay
            logger.info("Replaying event due to: {}", replayReason);
            // Skip side effects during replay
        } else {
            // Normal processing
            sendEmailNotification(event);
        }
    }
    // end::event-handler-replay-context[]

    private void sendEmailNotification(OrderPlacedEvent event) {
    }
}
