package messagingconcepts.messagecorrelation.springboot;

import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
import org.axonframework.messaging.core.correlation.MessageOriginProvider;
import org.axonframework.messaging.core.correlation.SimpleCorrelationDataProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// tag::spring-config[]
@Configuration
public class CorrelationDataProviderConfiguration {

    // Configuring CorrelationDataProvider beans overrides the default MessageOriginProvider
    @Bean
    public CorrelationDataProvider messageOriginProvider() {
        return new MessageOriginProvider();
    }

    @Bean
    public CorrelationDataProvider tenantCorrelationProvider() {
        return new SimpleCorrelationDataProvider("tenantId", "userId");
    }
}
// end::spring-config[]
