package testing.basictesting.given.singleevent;

import testing.basictesting.fixtures.AccountCreatedEvent;
import testing.basictesting.fixtures.MoneyDepositedEvent;

// tag::given-single-event[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               .event(new AccountCreatedEvent("account-1", 500.00))
               .event(new MoneyDepositedEvent("account-1", 100.00));
               // when...
    }
}
// end::given-single-event[]
