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
package queries.queryhandlers.matching;

import java.util.Map;

import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

public class PayloadConversionHandlers {

    private Map<String, CardSummary> cardSummaryStorage;
    private Map<String, CardDetails> detailedCardStorage;

    // tag::payload-conversion-handlers[]
    // Handler expecting the query as a specific type
    @QueryHandler
    public CardSummary handle(FetchCardSummaryQuery query) {
        return cardSummaryStorage.get(query.getCardSummaryId());
    }

    // Another handler in another application could receive the same query in a different representation
    @QueryHandler(queryName = "giftcard.FetchCardSummary") // Must specify queryName!
    public CardDetails handle(Map<String, Object> query) {
        String cardId = (String) query.get("cardSummaryId");
        return detailedCardStorage.get(cardId);
    }
    // end::payload-conversion-handlers[]
}

class CardDetails {
}
