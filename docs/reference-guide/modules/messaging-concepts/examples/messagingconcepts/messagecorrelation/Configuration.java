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
package messagingconcepts.messagecorrelation;

import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
import org.axonframework.messaging.core.correlation.MessageOriginProvider;
import org.axonframework.messaging.core.correlation.MultiCorrelationDataProvider;
import org.axonframework.messaging.core.correlation.SimpleCorrelationDataProvider;

import java.util.List;

// tag::multi-provider[]
public class Configuration {

    public CorrelationDataProvider customCorrelationDataProviders() {
        return new MultiCorrelationDataProvider(
            List.of(
                new MessageOriginProvider(),
                new SimpleCorrelationDataProvider("tenantId", "userId")
            )
        );
    }
}
// end::multi-provider[]
