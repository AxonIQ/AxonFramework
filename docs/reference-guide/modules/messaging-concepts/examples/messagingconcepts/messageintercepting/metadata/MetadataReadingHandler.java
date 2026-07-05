package messagingconcepts.messageintercepting.metadata;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.annotation.MetadataValue;

public class MetadataReadingHandler {

    // tag::metadata-value-handler[]
    @CommandHandler
    public void handle(MyCommand command,
                       @MetadataValue(value = "userId", required = false) String userId) {
        // userId was extracted from Reactor context by the dispatch interceptor
    }
    // end::metadata-value-handler[]
}
