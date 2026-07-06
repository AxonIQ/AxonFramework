package migration.paths.projectorseventprocessors.configuration;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;

// tag::event-processor-configuration[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;

public class EventProcessorConfigurationExample {

    public void configure(MessagingConfigurer configurer) {
        configurer.eventProcessing(
                eventProcessing -> eventProcessing.pooledStreaming(
                        pooledStreaming -> pooledStreaming.processor(
                                "my-processor",
                                module -> module.eventHandlingComponents(
                                                         components -> components.autodetected(
                                                                 "my-projector",
                                                                 cfg -> new MyProjector()
                                                         )
                                                 )
                                                 .notCustomized()
                        )
                )
        );
    }
}
// end::event-processor-configuration[]

class MyProjector {

    @EventHandler
    public void on(MyEvent event) {
        // ...
    }
}

record MyEvent() {

}
