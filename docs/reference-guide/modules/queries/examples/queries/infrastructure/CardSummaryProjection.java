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

// tag::emitting-updates[]
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.springframework.stereotype.Component;

@Component
public class CardSummaryProjection {

    @EventHandler
    public void on(CardRedeemedEvent event, ProcessingContext context) {
        // Create a context-aware emitter
        QueryUpdateEmitter emitter = QueryUpdateEmitter.forContext(context);

        // Update the model
        CardSummary summary = new CardSummary(event.cardId(), event.amount());

        // Emit update to subscription queries
        emitter.emit(
            FetchCardSummaryQuery.class,
            query -> query.cardSummaryId().equals(event.cardId()),
            summary
        );
    }
}
// end::emitting-updates[]
