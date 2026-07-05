package tuning.commandprocessing.disabling;

import org.axonframework.messaging.core.configuration.MessagingConfigurationDefaults;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.sequencing.NoOpSequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;

class AxonConfig {

    // tag::disable-policy[]
    public void configureSequencingPolicy(MessagingConfigurer configurer) {
        configurer.componentRegistry(cr -> cr.registerComponent(
                SequencingPolicy.class,
                MessagingConfigurationDefaults.COMMAND_SEQUENCING_POLICY,
                c -> NoOpSequencingPolicy.INSTANCE)
        );
    }
    // end::disable-policy[]
}
