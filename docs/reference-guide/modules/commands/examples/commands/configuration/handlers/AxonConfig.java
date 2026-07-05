package commands.configuration.handlers;

import org.axonframework.modelling.repository.Repository;

// tag::command-handling-module[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;

public class AxonConfig {

    public void configureCommandHandlers() {
        MessagingConfigurer.create()
            .registerCommandHandlingModule(
                CommandHandlingModule.named("gift-card-commands")
                    .commandHandlers(handlers ->
                        handlers.autodetectedCommandHandlingComponent(
                            config -> new GiftCardCommandHandler(
                                config.getComponent(Repository.class)
                            )
                        )
                    )
            );
    }
}
// end::command-handling-module[]
