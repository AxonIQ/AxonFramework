package testing.basictesting.when.singleeventwithmetadata;

import testing.basictesting.fixtures.ExternalPaymentReceivedEvent;

// tag::when-single-event-with-metadata[]
import org.axonframework.messaging.core.Metadata;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .event(
                   new ExternalPaymentReceivedEvent("account-1", 100.00),
                   Metadata.with("userId", "user-123")
               );
               // then...
    }
}
// end::when-single-event-with-metadata[]
