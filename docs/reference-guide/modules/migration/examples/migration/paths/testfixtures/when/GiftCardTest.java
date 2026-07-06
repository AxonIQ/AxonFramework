package migration.paths.testfixtures.when;

import migration.paths.testfixtures.fixtures.AxonConfig;
import migration.paths.testfixtures.fixtures.CardIssuedEvent;
import migration.paths.testfixtures.fixtures.RedeemCardCommand;

// tag::when-phase[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class GiftCardTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        ApplicationConfigurer configurer = AxonConfig.appConfigurer();
        fixture = AxonTestFixture.with(configurer);
    }

    @Test
    void whenCommand() {
        fixture.given()
               .event(new CardIssuedEvent("card-1", 100))
               .when()
               .command(new RedeemCardCommand("card-1", 30));
               // then...
    }

    @Test
    void whenCommandWithMetadata() {
        fixture.given()
               .event(new CardIssuedEvent("card-1", 100))
               .when()
               .command(new RedeemCardCommand("card-1", 30),
                        Metadata.with("userId", "user-123"));
               // then...
    }
}
// end::when-phase[]
