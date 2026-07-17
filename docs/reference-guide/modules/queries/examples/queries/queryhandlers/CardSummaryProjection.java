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
package queries.queryhandlers;

import java.util.Map;

// tag::card-summary-projection[]
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

public class CardSummaryProjection {

    private Map<String, CardSummary> cardSummaryStorage;

    @QueryHandler // <1>
    public CardSummary handle(FetchCardSummaryQuery query) { // <2>
        return cardSummaryStorage.get(query.getCardSummaryId());
    }
    // omitted CardSummary event handlers which update the model
}
// end::card-summary-projection[]

class CardSummary {
}
