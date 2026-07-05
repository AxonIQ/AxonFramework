package events.eventpublishing.eventtagging.configapi;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.Tag;

import java.util.Set;

class TagResolverConfig {

    // tag::register-tag-resolver-config-api[]
    public EventSourcingConfigurer configureTagResolver(EventSourcingConfigurer configurer) {
        return configurer.registerTagResolver(config -> new CustomTagResolver());
    }
    // end::register-tag-resolver-config-api[]
}

class CustomTagResolver implements TagResolver {

    @Override
    public Set<Tag> resolve(EventMessage event) {
        return Set.of();
    }
}
