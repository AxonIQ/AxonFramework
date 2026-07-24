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

// The import is indented to the depth of the nested method below, so that the
// indent=0 normalization of the include renders both regions flush left.
// tag::metadata-and-value-parameters-import[]
    import org.axonframework.messaging.core.Metadata;
    import org.axonframework.messaging.core.annotation.MetadataValue;

// end::metadata-and-value-parameters-import[]
import java.util.Map;

import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetadataParametersExample {

    private static final Logger logger = LoggerFactory.getLogger(MetadataParametersExample.class);

    private Map<String, CardSummary> cardSummaryStorage;

    // tag::metadata-and-value-parameters[]
    @QueryHandler
    public CardSummary handle(FetchCardSummaryQuery query,
                              Metadata metadata,
                              @MetadataValue("userId") String userId) {
        logger.info("Query from user: {}", userId);
        return cardSummaryStorage.get(query.getCardSummaryId());
    }
    // end::metadata-and-value-parameters[]
}
