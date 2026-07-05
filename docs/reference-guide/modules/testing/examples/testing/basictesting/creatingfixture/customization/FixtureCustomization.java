package testing.basictesting.creatingfixture.customization;

import testing.basictesting.fixtures.Account;

import java.time.Instant;

// tag::fixture-with-customization-import[]
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

// end::fixture-with-customization-import[]

// Illustrative event type only used to demonstrate registerIgnoredField(); not shown in the documentation.
record MyEvent(String eventId, Instant timestamp) {

}

// tag::fixture-with-customization[]
class AccountTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        EventSourcingConfigurer configurer =
                EventSourcingConfigurer.create()
                                       .registerEntity(EventSourcedEntityModule.autodetected(
                                           String.class, Account.class
                                       ));
        fixture = AxonTestFixture.with(
                configurer,
                customization -> customization.registerIgnoredField(MyEvent.class, "timestamp")
                                              .registerIgnoredField(MyEvent.class, "eventId")
        );
    }

    @AfterEach
    void tearDown() {
        fixture.stop();  // Always stop the fixture!
    }
    // tests...
}
// end::fixture-with-customization[]
