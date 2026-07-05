package testing.basictesting.then.multiplecommands;

import testing.basictesting.fixtures.AccountClosedEvent;
import testing.basictesting.fixtures.DeregisterAccountCommand;
import testing.basictesting.fixtures.SendEmailCommand;

// tag::then-multiple-commands[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .event(new AccountClosedEvent("account-1"))
               .then()
               .commands(
                       new SendEmailCommand("user@example.com", "Welcome!"),
                       new DeregisterAccountCommand("account-1")
               );
    }
}
// end::then-multiple-commands[]
