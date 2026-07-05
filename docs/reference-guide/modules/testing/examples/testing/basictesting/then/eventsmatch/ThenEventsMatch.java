package testing.basictesting.then.eventsmatch;

import testing.basictesting.fixtures.MoneyWithdrawnEvent;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::then-events-match[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .command(new WithdrawMoneyCommand("account-1", 100.00))
               .then()
               .eventsMatch(events -> events.size() == 2
                       && events.getFirst().payloadAs(MoneyWithdrawnEvent.class)
                                .accountId()
                                .equals("account-1"));
    }
}
// end::then-events-match[]
