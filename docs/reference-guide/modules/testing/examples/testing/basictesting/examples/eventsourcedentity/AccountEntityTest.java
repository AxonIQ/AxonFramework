package testing.basictesting.examples.eventsourcedentity;

import testing.basictesting.fixtures.AccountClosedEvent;
import testing.basictesting.fixtures.AccountCreatedEvent;
import testing.basictesting.fixtures.CloseAccountCommand;
import testing.basictesting.fixtures.CreateAccountCommand;
import testing.basictesting.fixtures.DepositMoneyCommand;
import testing.basictesting.fixtures.MoneyDepositedEvent;
import testing.basictesting.fixtures.MoneyWithdrawnEvent;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::testing-event-sourced-entity[]
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountEntityTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        EventSourcingConfigurer configurer =
                EventSourcingConfigurer.create().registerEntity(
                    EventSourcedEntityModule.autodetected(String.class, Account.class)
                );

        fixture = AxonTestFixture.with(configurer);
    }

    @Test
    void accountLifecycleReactsAsExpected() {
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new CreateAccountCommand("account-1", 500.00))
               .then()
               .success()
               .events(new AccountCreatedEvent("account-1", 500.00))
               .and()
               .when()
               .command(new DepositMoneyCommand("account-1", 100.00))
               .then()
               .success()
               .events(new MoneyDepositedEvent("account-1", 100.00))
               .and()
               .when()
               .command(new WithdrawMoneyCommand("account-1", 200.00))
               .then()
               .success()
               .events(new MoneyWithdrawnEvent("account-1", 200.00))
               .and()
               .when()
               .command(new CloseAccountCommand("account-1"))
               .then()
               .success()
               .events(new AccountClosedEvent("account-1"));
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }
}
// end::testing-event-sourced-entity[]
