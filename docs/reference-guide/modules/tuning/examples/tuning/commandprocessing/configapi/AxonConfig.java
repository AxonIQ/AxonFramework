package tuning.commandprocessing.configapi;

import org.axonframework.messaging.core.configuration.MessagingConfigurationDefaults;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import tuning.commandprocessing.TenantBasedSequencingPolicy;

class AxonConfig {

    // tag::register-policy[]
    public void configureSequencingPolicy(MessagingConfigurer configurer) {
        configurer.componentRegistry(cr -> cr.registerComponent(
                SequencingPolicy.class,
                MessagingConfigurationDefaults.COMMAND_SEQUENCING_POLICY,
                c -> new TenantBasedSequencingPolicy())
        );
    }
    // end::register-policy[]
}
