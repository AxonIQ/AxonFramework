package messagingconcepts.messageintercepting.command;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

// tag::command-dispatch-metadata[]
public class MetadataEnrichingInterceptor
        implements MessageDispatchInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnDispatch(
            CommandMessage command,
            ProcessingContext context,
            MessageDispatchInterceptorChain<CommandMessage> chain
    ) {
        // Add metadata
        CommandMessage enrichedCommand = command.andMetadata(
            Metadata.with("timestamp", String.valueOf(System.currentTimeMillis()))
        );

        return chain.proceed(enrichedCommand, context);
    }
}
// end::command-dispatch-metadata[]
