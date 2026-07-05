package testing.basictesting.then.exceptionsatisfies;

import testing.basictesting.fixtures.InsufficientBalanceException;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::then-exception-satisfies[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .command(new WithdrawMoneyCommand("account-1", 100.00))
               .then()
               .exceptionSatisfies(ex -> {
                   assertInstanceOf(InsufficientBalanceException.class, ex);
                   var ibe = (InsufficientBalanceException) ex;
                   assertEquals("account-1", ibe.accountId());
                   assertEquals(100.00, ibe.requestedAmount());
               });
    }
}
// end::then-exception-satisfies[]
