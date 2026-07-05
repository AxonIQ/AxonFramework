package messagingconcepts.processingcontext;

import java.util.ArrayList;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

class ResourceUpdateHandler implements ResourceKeyDefinitions {

    // tag::updating-resources[]
    @EventHandler
    public void on(OrderPlacedEvent event, ProcessingContext context) {
        // Process event...
        // and update resource using a function
        context.updateResource(TAGS, tags -> {
            if (tags == null) {
                tags = new ArrayList<>();
            }
            tags.add("processed");
            return tags;
        });
    }
    // end::updating-resources[]
}
