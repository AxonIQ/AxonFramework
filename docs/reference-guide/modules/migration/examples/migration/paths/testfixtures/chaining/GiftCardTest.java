package migration.paths.testfixtures.chaining;

import migration.paths.testfixtures.fixtures.AxonConfig;
import migration.paths.testfixtures.fixtures.CardIssuedEvent;
import migration.paths.testfixtures.fixtures.CardRedeemedEvent;
import migration.paths.testfixtures.fixtures.ReimburseCardCommand;
import migration.paths.testfixtures.fixtures.RedeemCardCommand;

// tag::chaining[]
import org.axonframework.common.configuration.ApplicationConfigurer;
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
    void completeTestFlow() {
        fixture.given()
               .events(new CardIssuedEvent("card-1", 100),
                       new CardRedeemedEvent("card-1", 20))
               .command(new ReimburseCardCommand("card-1", 10))
               .when()
               .command(new RedeemCardCommand("card-1", 30))
               .then()
               .success()
               .events(new CardRedeemedEvent("card-1", 30))
               .resultMessagePayload(null);
    }
}
// end::chaining[]
