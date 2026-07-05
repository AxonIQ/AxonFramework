package commands.infrastructure.registration;

import org.axonframework.modelling.repository.Repository;

// tag::manual-registration[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;

public class AxonConfig {

    public void configureHandlers() {
        MessagingConfigurer.create()
            .registerCommandHandlingModule(
                CommandHandlingModule.named("order-commands")
                    .commandHandlers(handlers ->
                        handlers.autodetectedCommandHandlingComponent(
                            config -> new OrderCommandHandler(
                                config.getComponent(Repository.class)
                            )
                        )
                    )
            );
    }
}
// end::manual-registration[]
