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

// tag::fetch-card-summary-query[]
import org.axonframework.messaging.queryhandling.annotation.Query;

@Query(namespace = "giftcard", name = "FetchCardSummary", version = "1.0")
public class FetchCardSummaryQuery {

    private final String cardSummaryId;

    public FetchCardSummaryQuery(String cardSummaryId) {
        this.cardSummaryId = cardSummaryId;
    }
    // omitted getters, equals/hashCode, toString functions
    // end::fetch-card-summary-query[]

    public String getCardSummaryId() {
        return cardSummaryId;
    }
    // tag::fetch-card-summary-query[]
}
// end::fetch-card-summary-query[]
