package testing.matchersandfieldfilters.matchers.noevents;

import testing.matchersandfieldfilters.fixtures.CompleteOrderCommand;

// tag::no-events[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.axonframework.test.matchers.Matchers;
import org.junit.jupiter.api.*;

import static org.axonframework.test.matchers.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void noEvents() {
        fixture.when()
               .command(new CompleteOrderCommand("unknown"))
               .then()
               .eventsSatisfy(events -> assertThat(events, Matchers.noEvents()));
    }
}
// end::no-events[]
