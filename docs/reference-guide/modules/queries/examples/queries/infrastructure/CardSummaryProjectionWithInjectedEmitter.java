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
package queries.infrastructure;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;

class CardSummaryProjectionWithInjectedEmitter {

    // tag::emitting-updates-injected[]
    @EventHandler
    public void on(CardRedeemedEvent event, QueryUpdateEmitter emitter) {
        // Axon automatically provides the context-aware emitter
        emitter.emit(FetchCardSummaryQuery.class,
                     query -> query.cardSummaryId().equals(event.cardId()),
                     new CardSummary(event.cardId(), event.amount()));
    }
    // end::emitting-updates-injected[]
}
