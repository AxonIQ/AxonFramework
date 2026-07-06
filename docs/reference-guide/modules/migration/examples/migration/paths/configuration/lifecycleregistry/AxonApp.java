package migration.paths.configuration.lifecycleregistry;

// tag::lifecycle-registry[]
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

import java.util.concurrent.CompletableFuture;

class AxonApp {

    public static void main(String[] args) {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();

        configurer.lifecycleRegistry(lr -> {
            lr.onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, config -> {
                // Startup logic
                return CompletableFuture.completedFuture(null);
            });

            lr.onShutdown(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, config -> {
                // Shutdown logic
                return CompletableFuture.completedFuture(null);
            });
        });
    }
}
// end::lifecycle-registry[]
