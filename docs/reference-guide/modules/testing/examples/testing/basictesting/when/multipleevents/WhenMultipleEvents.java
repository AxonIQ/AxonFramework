package testing.basictesting.when.multipleevents;

import testing.basictesting.fixtures.ExternalPaymentReceivedEvent;
import testing.basictesting.fixtures.PaymentConfirmedEvent;

// tag::when-multiple-events[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .events(
                   new ExternalPaymentReceivedEvent("account-1", 100.00),
                   new PaymentConfirmedEvent("account-1")
               );
               // then...
    }
}
// end::when-multiple-events[]
