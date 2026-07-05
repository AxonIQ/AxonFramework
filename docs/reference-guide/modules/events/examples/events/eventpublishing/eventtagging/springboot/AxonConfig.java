package events.eventpublishing.eventtagging.springboot;

import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

// tag::register-tag-resolver-spring-boot[]
@Configuration
public class AxonConfig {

    @Bean
    public TagResolver tagResolver() {
        return new CustomTagResolver();
    }
}
// end::register-tag-resolver-spring-boot[]

class CustomTagResolver implements TagResolver {

    @Override
    public Set<Tag> resolve(EventMessage event) {
        return Set.of();
    }
}
