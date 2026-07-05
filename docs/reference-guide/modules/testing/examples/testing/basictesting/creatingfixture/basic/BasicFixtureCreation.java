package testing.basictesting.creatingfixture.basic;

import testing.basictesting.fixtures.Account;

// tag::basic-fixture-creation[]
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        EventSourcingConfigurer configurer =
            EventSourcingConfigurer.create()
                                   .registerEntity(EventSourcedEntityModule.autodetected(
                                       String.class, Account.class
                                   ));
        fixture = AxonTestFixture.with(configurer);
    }

    @AfterEach
    void tearDown() {
        fixture.stop();  // Always stop the fixture
    }
    // tests...
}
// end::basic-fixture-creation[]
