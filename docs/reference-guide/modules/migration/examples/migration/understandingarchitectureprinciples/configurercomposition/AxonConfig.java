package migration.understandingarchitectureprinciples.configurercomposition;

// tag::configurer-composition[]
import io.axoniq.axonserver.connector.AxonServerConnection;
import io.axoniq.framework.axonserver.connector.event.AxonServerEventStorageEngine;
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.core.interception.LoggingInterceptor;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;

class AxonConfig {

    public ApplicationConfigurer configurer() {
        return EventSourcingConfigurer.create()
                                      .registerEventStorageEngine(config -> new AxonServerEventStorageEngine(
                                              config.getComponent(AxonServerConnection.class),
                                              config.getComponent(EventConverter.class)
                                      ))
                                      // Accessing the modelling layer...
                                      .modelling(modelling -> {
                                          CommandHandlingModule.CommandHandlerPhase commandHandlingModule =
                                                  CommandHandlingModule.named("orders")
                                                                       .commandHandlers();
                                          modelling.registerCommandHandlingModule(commandHandlingModule);
                                      })
                                      // Accessing the messaging layer...
                                      .messaging(messaging -> messaging.registerDispatchInterceptor(
                                              config -> new LoggingInterceptor<>()
                                      ));
    }
}
// end::configurer-composition[]
