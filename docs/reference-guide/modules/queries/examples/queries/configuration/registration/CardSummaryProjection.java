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
package queries.configuration.registration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

// tag::card-summary-projection[]
public class CardSummaryProjection {

    private final Map<String, CardSummary> cardSummaryStorage = new ConcurrentHashMap<>();

    @QueryHandler
    public CardSummary handle(FetchCardSummaryQuery query) {
        // Retrieve CardSummary instance, for example from a repository
        return cardSummaryStorage.get(query.getCardSummaryId());
    }
}
// end::card-summary-projection[]
