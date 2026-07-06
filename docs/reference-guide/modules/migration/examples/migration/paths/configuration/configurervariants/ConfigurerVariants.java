package migration.paths.configuration.configurervariants;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.modelling.configuration.ModellingConfigurer;

public class ConfigurerVariants {

    public void chooseConfigurer() {
        // tag::configurer-variants[]
        // Messaging only
        MessagingConfigurer messagingConfigurer = MessagingConfigurer.create();

        // Messaging + entity modelling (no event sourcing)
        ModellingConfigurer modellingConfigurer = ModellingConfigurer.create();
        // end::configurer-variants[]
    }
}
