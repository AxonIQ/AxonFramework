package testing.matchersandfieldfilters.matchers.exactsequence;

import testing.matchersandfieldfilters.fixtures.CompleteOrderCommand;
import testing.matchersandfieldfilters.fixtures.OrderCompletedEvent;
import testing.matchersandfieldfilters.fixtures.PaymentProcessedEvent;

// tag::exact-sequence-of-exact-classes[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import static org.axonframework.test.matchers.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void exactSequenceOfExactClasses() {
        fixture.when()
               .command(new CompleteOrderCommand("order-1"))
               .then()
               .eventsSatisfy(events -> assertThat(events, exactSequenceOf(
                       messageWithPayload(exactClassOf(OrderCompletedEvent.class)),
                       messageWithPayload(exactClassOf(PaymentProcessedEvent.class)),
                       andNoMore()
               )));
    }
}
// end::exact-sequence-of-exact-classes[]
