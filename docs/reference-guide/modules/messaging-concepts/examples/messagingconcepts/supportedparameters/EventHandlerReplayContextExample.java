/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
