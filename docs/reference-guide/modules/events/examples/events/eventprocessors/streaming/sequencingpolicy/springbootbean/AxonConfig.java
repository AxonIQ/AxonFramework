package events.eventprocessors.streaming.sequencingpolicy.springbootbean;

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.sequencing.FullConcurrencyPolicy;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// tag::sequencing-policy-spring-boot-bean[]
@Configuration
public class AxonConfig {
    // omitting other configuration methods...
    @Bean
    public SequencingPolicy<? super EventMessage> customSequencingPolicy() {
        return FullConcurrencyPolicy.INSTANCE;
    }
}
// end::sequencing-policy-spring-boot-bean[]
