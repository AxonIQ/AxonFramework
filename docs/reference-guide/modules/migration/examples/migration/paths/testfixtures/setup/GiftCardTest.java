package migration.paths.testfixtures.setup;

import migration.paths.testfixtures.fixtures.GiftCard;

// tag::fixture-setup[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class GiftCardTest {

    private AxonTestFixture testFixture;

    @BeforeEach
    void setUp() {
        // Reuse of the ApplicationConfigurer, ensuring components are configured once:
        ApplicationConfigurer configurer = AxonConfig.appConfigurer();

        testFixture = AxonTestFixture.with(configurer);
    }
}

class AxonConfig {

    // Static construction is an example for ApplicationConfigurer reuse in tests, but not mandatory
    public static ApplicationConfigurer appConfigurer() {
        return EventSourcingConfigurer.create()
                                      .registerEntity(giftCardModule())
                                      .messaging(AxonConfig::messagingCustomization);
    }

    private static EventSourcedEntityModule<String, GiftCard> giftCardModule() {
        return EventSourcedEntityModule.autodetected(String.class, GiftCard.class);
    }

    private static MessagingConfigurer messagingCustomization(MessagingConfigurer configurer) {
        return configurer.registerCommandHandlerInterceptor(
                c -> new MyCommandHandlingInterceptor()
        );
    }
}
// end::fixture-setup[]
