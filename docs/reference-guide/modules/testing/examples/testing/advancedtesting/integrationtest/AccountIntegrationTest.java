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
package testing.advancedtesting.integrationtest;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;

// tag::integration-test-import[]
import org.axonframework.modelling.repository.Repository;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;


// end::integration-test-import[]

// Minimal event and entity used to source the account that PlaceOrderCommand targets; not otherwise elaborated in
// this example.
record AccountCreatedEvent(@EventTag String accountId, int balance) {

}

@EventSourcedEntity(tagKey = "accountId")
class Account {

    @EntityCreator
    public Account() {
    }

    @EventSourcingHandler
    void on(AccountCreatedEvent event) {
        // Not otherwise elaborated in this example.
    }
}

// Minimal command and handler used only to demonstrate dispatching in the integration test's when-phase; not
// otherwise elaborated in this example.
record PlaceOrderCommand(String orderId, @TargetEntityId String accountId) {

}

class OrderCommandHandler {

    @CommandHandler
    void handle(PlaceOrderCommand command, @InjectEntity Account account) {
        // Not otherwise elaborated in this example.
    }
}

// Production configuration class, constructed statically and accessed as such for tests.
class MainApp {

    static EventSourcingConfigurer configurer() {
        return EventSourcingConfigurer.create()
                                      .registerEntity(EventSourcedEntityModule.autodetected(
                                              String.class, Account.class
                                      ))
                                      .messaging(messaging -> messaging.registerCommandHandlingModule(
                                              CommandHandlingModule.named("orders")
                                                                   .commandHandlers()
                                                                   .autodetectedCommandHandlingComponent(c -> new OrderCommandHandler())
                                      ));
    }
}

// tag::integration-test-class[]
class AccountIntegrationTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        // One way or another, you need access to the full ApplicationConfigurer.
        // In this example, the ApplicationConfigurer is constructed statically and accessed as such for tests.
        fixture = AxonTestFixture.with(MainApp.configurer());
    }

    @Test
    void test() {
        fixture.given()
               .events(new AccountCreatedEvent("account-1", 1337))
               .execute(config -> {
                   // Retrieve components for setup
                   var repository = config.getComponent(Repository.class);
                   // Perform setup...
               })
               .when()
               .command(new PlaceOrderCommand("order-1", "account-1"))
               .then()
               .expect(config -> {
                   // Retrieve components for verification
                   Repository repository = config.getComponent(Repository.class);
                   // Perform verification...
                   assertThat(repository).isNotNull();
               });
    }

    @AfterEach
    void tearDown() {
        // Ensure to stop the fixture to cleanly close your resources
        fixture.stop();
    }
}
// end::integration-test-class[]
