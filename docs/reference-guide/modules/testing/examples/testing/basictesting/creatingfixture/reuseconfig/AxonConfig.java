package testing.basictesting.creatingfixture.reuseconfig;

import testing.basictesting.fixtures.Account;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.eventhandling.configuration.EventProcessingConfigurer;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

// Minimal entity used only to demonstrate registering multiple entities; not otherwise elaborated in this example.
@EventSourcedEntity(tagKey = "orderId")
class Order {

    @EntityCreator
    public Order() {
    }
}

// Minimal command payload and handler used only to demonstrate registering multiple command handling modules; not
// otherwise elaborated in this example.
record ProcessPaymentCommand(@TargetEntityId String orderId, double amount) {

}

class PaymentCommandHandler {

    @CommandHandler
    void handle(ProcessPaymentCommand command) {
        // Handle payment processing
    }
}

// A minimal stand-in for the event handling component elaborated in a dedicated example elsewhere on this page.
class NotificationEventHandler {

}

// tag::reuse-production-configuration-api[]
// Production configuration class
public class AxonConfig {

    public static EventSourcingConfigurer configurer() {
        return EventSourcingConfigurer.create()
                                      .registerEntity(EventSourcedEntityModule.autodetected(
                                              String.class, Account.class
                                      ))
                                      .registerEntity(EventSourcedEntityModule.autodetected(
                                              String.class, Order.class
                                      ))
                                      .messaging(messaging -> messaging.registerCommandHandlingModule(
                                              CommandHandlingModule.named("payment")
                                                                   .commandHandlers()
                                                                   .autodetectedCommandHandlingComponent(c -> new PaymentCommandHandler())
                                      ))
                                      .messaging(messaging -> messaging.eventProcessing(AxonConfig::registerEventHandlers));
    }

    private static EventProcessingConfigurer registerEventHandlers(EventProcessingConfigurer eventProcessing) {
        return eventProcessing.pooledStreaming(pooledConfig -> pooledConfig.processor(
                "my-processor",
                processorConfig -> processorConfig.eventHandlingComponents(components -> components.autodetected(
                        "account-notifications", c -> new NotificationEventHandler())
                ).notCustomized()
        ));
    }
}

// Test class
class AccountTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = AxonTestFixture.with(AxonConfig.configurer());
    }

    @AfterEach
    void tearDown() {
        fixture.stop();  // Always stop the fixture!
    }
    // tests...
}
// end::reuse-production-configuration-api[]
