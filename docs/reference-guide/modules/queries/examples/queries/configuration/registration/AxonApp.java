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

// tag::query-configuration-api[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.axonframework.common.configuration.AxonConfiguration;

public class AxonApp {

    public static void main(String[] args) {
        QueryHandlingModule cardSummaryModule =
                QueryHandlingModule.named("card-summary-projection")
                                   .queryHandlers()
                                   .autodetectedQueryHandlingComponent(config -> new CardSummaryProjection())
                                   .build();
        MessagingConfigurer configurer =
            MessagingConfigurer.create()
                               .registerQueryHandlingModule(cardSummaryModule);
        // Build and start the configuration
        AxonConfiguration configuration = configurer.build();
        configuration.start();
    }
}
// end::query-configuration-api[]
