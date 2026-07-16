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
package testing.basictesting.examples.commandhandling;

import testing.basictesting.fixtures.Account;
import testing.basictesting.fixtures.AccountCreatedEvent;
import testing.basictesting.fixtures.CreateAccountCommand;
import testing.basictesting.fixtures.InsufficientBalanceException;
import testing.basictesting.fixtures.MoneyWithdrawnEvent;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::testing-command-handling-component[]
import org.axonframework.common.configuration.ModuleBuilder;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountCommandHandlerTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        EventSourcedEntityModule<String, Account> eventSourcedEntity =
                EventSourcedEntityModule.autodetected(String.class, Account.class);
        ModuleBuilder<CommandHandlingModule> accountCommandHandlingModule =
                CommandHandlingModule.named("account")
                                     .commandHandlers()
                                     .autodetectedCommandHandlingComponent(c -> new AccountCommandHandler());
        EventSourcingConfigurer configurer =
                EventSourcingConfigurer.create()
                                       .registerEntity(eventSourcedEntity)
                                       .messaging(messaging -> messaging.registerCommandHandlingModule(
                                               accountCommandHandlingModule
                                       ));

        fixture = AxonTestFixture.with(configurer);
    }

    @Test
    void createAccount() {
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new CreateAccountCommand("account-1", 500.00))
               .then()
               .success()
               .events(new AccountCreatedEvent("account-1", 500.00));
    }

    @Test
    void withdrawMoney() {
        fixture.given()
               .event(new AccountCreatedEvent("account-1", 500.00))
               .when()
               .command(new WithdrawMoneyCommand("account-1", 100.00))
               .then()
               .success()
               .events(new MoneyWithdrawnEvent("account-1", 100.00));
    }

    @Test
    void withdrawInsufficientBalance() {
        fixture.given()
               .event(new AccountCreatedEvent("account-1", 50.00))
               .when()
               .command(new WithdrawMoneyCommand("account-1", 100.00))
               .then()
               .exception(InsufficientBalanceException.class)
               .noEvents();
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }
}
// end::testing-command-handling-component[]
