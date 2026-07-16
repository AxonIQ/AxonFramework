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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;

class EventHandlerQueryUpdateEmitterExample {

    private final Map<String, CardSummary> cardSummaryStorage = new ConcurrentHashMap<>();

    // tag::event-handler-query-update-emitter[]
    @EventHandler
    public void on(CardRedeemedEvent event, QueryUpdateEmitter emitter) {

        // Update the model
        CardSummary summary = cardSummaryStorage.get(event.getCardId());
        summary.setRemainingValue(event.getRemainingValue());

        // Emit update to subscription queries
        emitter.emit(FetchCardSummaryQuery.class,
                    query -> query.getCardSummaryId().equals(event.getCardId()),
                    summary);
    }
    // end::event-handler-query-update-emitter[]
}
