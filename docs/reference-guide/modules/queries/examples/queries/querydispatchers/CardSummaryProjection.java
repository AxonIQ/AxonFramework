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
package queries.querydispatchers;

import jakarta.persistence.EntityManager;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

import java.util.List;

class CardSummaryProjection {

    private EntityManager entityManager;

    // tag::subscription-query-handler[]
    @QueryHandler
    public List<CardSummary> handle(FetchCardSummariesQuery query) {
        return entityManager
            .createNamedQuery("CardSummary.fetch", CardSummary.class)
            .setParameter("idStartsWith", query.getFilter().getIdStartsWith())
            .setFirstResult(query.getOffset())
            .setMaxResults(query.getLimit())
            .getResultList();
    }
    // end::subscription-query-handler[]

    // tag::emitting-updates[]
    @EventHandler
    public void on(RedeemedEvent event, QueryUpdateEmitter emitter) {
        // <1>
        CardSummary summary = entityManager.find(CardSummary.class, event.getId());
        summary.setRemainingValue(summary.getRemainingValue() - event.getAmount());

        // <2>
        emitter.emit(
            FetchCardSummariesQuery.class, // <3>
            query -> event.getId().startsWith(query.getFilter().getIdStartsWith()), // <4>
            summary // <5>
        );
    }
    // end::emitting-updates[]
}
