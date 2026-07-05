package testing.basictesting.then.expectasync;

import testing.basictesting.fixtures.AccountBalance;
import testing.basictesting.fixtures.AccountClosedEvent;
import testing.basictesting.fixtures.GetBalanceQuery;

// tag::then-expect-async[]
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
               .expectAsync(config -> {
                   QueryGateway queryGateway = config.getComponent(QueryGateway.class);
                   // Return CompletableFuture, like the result from the QueryGateway
                   return queryGateway.query(
                                              new GetBalanceQuery("account-1"),
                                              AccountBalance.class
                                      )
                                      .thenAccept(balance -> assertEquals(400.00, balance.amount()));
               });
    }
}
// end::then-expect-async[]
