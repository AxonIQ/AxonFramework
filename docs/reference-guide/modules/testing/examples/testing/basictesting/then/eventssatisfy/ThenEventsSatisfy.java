package testing.basictesting.then.eventssatisfy;

import testing.basictesting.fixtures.MoneyWithdrawnEvent;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::then-events-satisfy[]
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
               .eventsSatisfy(events -> {
                   assertEquals(2, events.size());
                   MoneyWithdrawnEvent firstEvent =
                           events.getFirst().payloadAs(MoneyWithdrawnEvent.class);
                   assertEquals("account-1", firstEvent.accountId());
                   assertEquals(100.00, firstEvent.amount());
               });
    }
}
// end::then-events-satisfy[]
