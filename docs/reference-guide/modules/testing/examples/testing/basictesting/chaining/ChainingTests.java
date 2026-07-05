package testing.basictesting.chaining;

import testing.basictesting.fixtures.AccountCreatedEvent;
import testing.basictesting.fixtures.InsufficientBalanceException;
import testing.basictesting.fixtures.MoneyWithdrawnEvent;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::chaining-tests[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               .event(new AccountCreatedEvent("account-1", 500.00))
               .when()
               .command(new WithdrawMoneyCommand("account-1", 100.00))
               .then()
               .success()
               .events(new MoneyWithdrawnEvent("account-1", 100.00))
               .and()  // Chain to next test
               .when()
               .command(new WithdrawMoneyCommand("account-1", 500.00))
               .then()
               .exception(InsufficientBalanceException.class)
               .noEvents();
    }
}
// end::chaining-tests[]
