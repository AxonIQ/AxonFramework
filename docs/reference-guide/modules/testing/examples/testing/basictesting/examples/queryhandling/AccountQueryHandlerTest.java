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
package testing.basictesting.examples.queryhandling;

import testing.basictesting.fixtures.AccountBalance;
import testing.basictesting.fixtures.AccountCreatedEvent;
import testing.basictesting.fixtures.GetBalanceQuery;
import testing.basictesting.fixtures.MoneyDepositedEvent;

// tag::testing-query-handling-component[]
import org.axonframework.common.configuration.ModuleBuilder;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class AccountQueryHandlerTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        ModuleBuilder<QueryHandlingModule> accountQueryHandlingModule =
                QueryHandlingModule.named("account")
                                   .queryHandlers()
                                   .autodetectedQueryHandlingComponent(
                                           c -> c.getComponent(AccountQueryHandler.class)
                                   );
        MessagingConfigurer configurer =
                MessagingConfigurer.create()
                                   .componentRegistry(cr -> cr.registerComponent(
                                           AccountQueryHandler.class, c -> new AccountQueryHandler()
                                   ))
                                   .registerQueryHandlingModule(accountQueryHandlingModule)
                                   .eventProcessing(
                                           processing -> processing.pooledStreaming(
                                                   pooledStreaming -> pooledStreaming.processor(
                                                           "notifications",
                                                           AccountQueryHandlerTest::configurePooledProcessor
                                                   )
                                           )
                                   );

        fixture = AxonTestFixture.with(configurer);
    }

    private static PooledStreamingEventProcessorModule configurePooledProcessor(
            EventProcessorModule.EventHandlingPhase<PooledStreamingEventProcessorModule, PooledStreamingEventProcessorConfiguration> processor
    ) {
        return processor.eventHandlingComponents(components -> components.autodetected(
                                "account-projection", c -> c.getComponent(AccountQueryHandler.class))
                        )
                        .notCustomized();
    }

    @Test
    void testGetAccountBalance() {
        fixture.given()
               .event(new AccountCreatedEvent("account-1", 500.00))
               .event(new MoneyDepositedEvent("account-1", 100.00))
               .when()
               .nothing()
               .then()
               .expect(config -> {
                   QueryGateway queryGateway = config.getComponent(QueryGateway.class);
                   var balance = queryGateway.query(
                           new GetBalanceQuery("account-1"),
                           AccountBalance.class
                   ).join();
                   assertEquals(600.00, balance.amount());
               });
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }
}
// end::testing-query-handling-component[]
