package testing.basictesting.then.singlecommand;

import testing.basictesting.fixtures.AccountClosedEvent;
import testing.basictesting.fixtures.SendEmailCommand;

// tag::then-single-command[]
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
               .commands(new SendEmailCommand("user@example.com", "Welcome!"));
    }
}
// end::then-single-command[]
