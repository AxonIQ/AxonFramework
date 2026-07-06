package migration.paths.testfixtures.matchers;

import migration.paths.testfixtures.fixtures.AxonConfig;
import migration.paths.testfixtures.fixtures.CardIssuedEvent;
import migration.paths.testfixtures.fixtures.CardRedeemedEvent;
import migration.paths.testfixtures.fixtures.IssueCardCommand;
import migration.paths.testfixtures.fixtures.RedeemCardCommand;

// tag::matcher-based-validation[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class GiftCardTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        ApplicationConfigurer configurer = AxonConfig.appConfigurer();
        fixture = AxonTestFixture.with(configurer);
    }

    @Test
    void eventsSatisfy() {
        fixture.given()
               .event(new CardIssuedEvent("card-1", 100))
               .when()
               .command(new RedeemCardCommand("card-1", 30))
               .then()
               .eventsSatisfy(events -> {
                   assertEquals(1, events.size());
                   assertEquals(
                           new CardRedeemedEvent("card-1", 30),
                           events.getFirst().payload()
                   );
               });
    }

    @Test
    void eventsMatch() {
        fixture.given()
               .event(new CardIssuedEvent("card-1", 100))
               .when()
               .command(new RedeemCardCommand("card-1", 30))
               .then()
               .eventsMatch(events -> events.size() == 1 && Objects.equals(
                       events.getFirst().payload(), new CardRedeemedEvent("card-1", 30)
               ));
    }

    @Test
    void resultMessageSatisfies() {
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new IssueCardCommand("card-1", 100))
               .then()
               .resultMessageSatisfies(
                       result -> assertEquals("card-1", result.payload())
               );
    }
}
// end::matcher-based-validation[]
