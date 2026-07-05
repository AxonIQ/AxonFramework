package testing.matchersandfieldfilters.matchers.listwithallof;

import testing.matchersandfieldfilters.fixtures.InventoryIncrementedEvent;
import testing.matchersandfieldfilters.fixtures.OrderProcessedEvent;
import testing.matchersandfieldfilters.fixtures.ProcessOrderCommand;

// tag::list-with-all-of-matchers[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import static org.axonframework.test.matchers.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void listWithAllOfMatchers() {
        fixture.when()
               .command(new ProcessOrderCommand("order-1"))
               .then()
               .eventsSatisfy(events -> assertThat(events, payloadsMatching(
                       listWithAllOf(
                               matches(payload -> payload instanceof OrderProcessedEvent),
                               matches(payload -> payload instanceof InventoryIncrementedEvent)
                       )
               )));
    }
}
// end::list-with-all-of-matchers[]
