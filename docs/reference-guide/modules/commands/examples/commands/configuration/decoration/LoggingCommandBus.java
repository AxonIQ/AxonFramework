package commands.configuration.decoration;

import java.util.concurrent.CompletableFuture;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::logging-command-bus[]
public class LoggingCommandBus implements CommandBus {

    private final CommandBus delegate;
    private final Logger logger = LoggerFactory.getLogger(LoggingCommandBus.class);

    public LoggingCommandBus(CommandBus delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableFuture<CommandResultMessage> dispatch(
            CommandMessage command,
            ProcessingContext context) {
        logger.info("Dispatching command: {}", command.type().name());
        return delegate.dispatch(command, context)
            .whenComplete((result, error) -> {
                if (error != null) {
                    logger.error("Command failed: {}", command.type().name(), error);
                } else {
                    logger.info("Command succeeded: {}", command.type().name());
                }
            });
    }

    // Delegate other methods...
    // end::logging-command-bus[]

    @Override
    public CommandBus subscribe(QualifiedName name, CommandHandler handler) {
        delegate.subscribe(name, handler);
        return this;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
    }
    // tag::logging-command-bus[]
}
// end::logging-command-bus[]
