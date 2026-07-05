package tuning.commandprocessing.springboot;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tuning.commandprocessing.TenantBasedSequencingPolicy;

// tag::register-policy-spring-boot[]
@Configuration
class MessagingConfiguration {

    @Bean
    public SequencingPolicy<? super CommandMessage> commandSequencingPolicy() {
        return new TenantBasedSequencingPolicy();
    }
}
// end::register-policy-spring-boot[]
