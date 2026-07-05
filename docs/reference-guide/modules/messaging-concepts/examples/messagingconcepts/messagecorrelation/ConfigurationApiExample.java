package messagingconcepts.messagecorrelation;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.correlation.MessageOriginProvider;
import org.axonframework.messaging.core.correlation.SimpleCorrelationDataProvider;

class ConfigurationApiExample {

    // tag::config-api[]
    public void buildConfiguration() {
        MessagingConfigurer.create()
                           .registerCorrelationDataProvider(
                                   config -> new MessageOriginProvider()
                           )
                           .registerCorrelationDataProvider(
                                   config -> new SimpleCorrelationDataProvider("tenantId", "userId")
                           )
                           .start();
    }
    // end::config-api[]
}
