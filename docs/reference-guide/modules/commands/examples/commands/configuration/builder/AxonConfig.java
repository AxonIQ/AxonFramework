package commands.configuration.builder;

import java.lang.invoke.MethodHandles;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.modelling.repository.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::configuration-builder[]
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;

public class AxonConfig {
    // end::configuration-builder[]

    private static final Logger logger =
        LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    // tag::configuration-builder[]

    public AxonConfiguration buildConfiguration() {
        return EventSourcingConfigurer.create()
            // Register entities
            .registerEntity(EventSourcedEntityModule.autodetected(
                    String.class, GiftCard.class)
            )
            // Register command handlers
            .registerCommandHandlingModule(
                    CommandHandlingModule.named("gift-card-commands").commandHandlers(
                            handlers -> handlers.autodetectedCommandHandlingComponent(
                                    config -> new GiftCardCommandHandler(
                                            config.getComponent(Repository.class)
                                    )
                            )
                    )
            )
            // Register interceptors
            .messaging(messagingConfigurer -> messagingConfigurer.registerCommandHandlerInterceptor(
                    config -> (command, context, chain) -> {
                        logger.info("Handling: {}", command.type().name());
                        return chain.proceed(command, context);
                    }
            ))
            // Build and start
            .start();
    }
}
// end::configuration-builder[]
