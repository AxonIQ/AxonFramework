package testing.basictesting.then.awaitwithduration;

import testing.basictesting.fixtures.AccountClosedEvent;
import testing.basictesting.fixtures.SendEmailCommand;

// tag::then-await-with-duration[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import java.time.Duration;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .event(new AccountClosedEvent("account-1"))
               .then()
               .await(
                       then -> then.commands(new SendEmailCommand("user@example.com", "Welcome!")),
                       Duration.ofMillis(250)
               );
    }
}
// end::then-await-with-duration[]
