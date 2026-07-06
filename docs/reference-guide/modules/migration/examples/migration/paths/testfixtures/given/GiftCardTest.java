package migration.paths.testfixtures.given;

import migration.paths.testfixtures.fixtures.AxonConfig;
import migration.paths.testfixtures.fixtures.CardIssuedEvent;
import migration.paths.testfixtures.fixtures.CardRedeemedEvent;
import migration.paths.testfixtures.fixtures.IssueCardCommand;

// tag::given-phase[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import java.util.List;

class GiftCardTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        ApplicationConfigurer configurer = AxonConfig.appConfigurer();
        fixture = AxonTestFixture.with(configurer);
    }

    @Test
    void givenSingleEvent() {
        fixture.given()
               .event(new CardIssuedEvent("card-1", 100));
               // when...
    }

    @Test
    void givenMultipleEvents() {
        fixture.given()
               .events(new CardIssuedEvent("card-1", 100),
                       new CardRedeemedEvent("card-1", 20));
               // when...
    }

    @Test
    void givenEventsAsList() {
        fixture.given()
               .events(List.of(new CardIssuedEvent("card-1", 100),
                               new CardRedeemedEvent("card-1", 20)));
               // when...
    }

    @Test
    void givenCommands() {
        fixture.given()
               .command(new IssueCardCommand("card-1", 100));
               // when...
    }

    @Test
    void givenNoPriorActivity() {
        fixture.given()
               .noPriorActivity();
               // when...
    }
}
// end::given-phase[]
