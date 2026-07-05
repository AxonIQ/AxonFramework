package testing.basictesting.then.expect;

import testing.basictesting.fixtures.AccountBalance;
import testing.basictesting.fixtures.AccountClosedEvent;
import testing.basictesting.fixtures.GetBalanceQuery;

// tag::then-expect[]
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
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
               .event(new AccountClosedEvent("account-1"))
               .then()
               .expect(config -> {
                   AccountBalance balance =
                           config.getComponent(QueryGateway.class)
                                 .query(
                                         new GetBalanceQuery("account-1"),
                                         AccountBalance.class
                                 )
                                 .join();
                   assertEquals(400.00, balance.amount());
               });
    }
}
// end::then-expect[]
