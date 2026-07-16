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
package queries.queryhandlers.parameters;

import java.util.Map;

import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

public class ProcessingContextExample {

    private Map<String, CardSummary> cardSummaryStorage;

    // tag::query-handler-processing-context[]
    @QueryHandler
    public CardSummary handle(FetchCardSummaryQuery query, ProcessingContext context) {
        // Access resources
        SomeService service = context.getResource(SomeService.RESOURCE_KEY);

        return cardSummaryStorage.get(query.getCardSummaryId());
    }
    // end::query-handler-processing-context[]
}

class SomeService {

    static final ResourceKey<SomeService> RESOURCE_KEY = ResourceKey.withLabel("someService");
}
