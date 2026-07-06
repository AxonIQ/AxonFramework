package migration.paths.serializers;

// The imports are indented to the depth of the nested method below, so that the
// indent=0 normalization of the include renders both regions flush left.
// tag::declarative-configuration-import[]
    import org.axonframework.conversion.Converter;
    import org.axonframework.conversion.DelegatingGeneralConverter;
    import org.axonframework.conversion.GeneralConverter;
    import org.axonframework.conversion.jackson.JacksonConverter;
    import org.axonframework.messaging.core.configuration.MessagingConfigurer;
    import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
    import org.axonframework.messaging.core.conversion.MessageConverter;
    import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
    import org.axonframework.messaging.eventhandling.conversion.EventConverter;

// end::declarative-configuration-import[]
import tools.jackson.databind.ObjectMapper;

class DeclarativeConversionConfiguration {

    // tag::declarative-configuration[]
    public void configure(MessagingConfigurer configurer, ObjectMapper objectMapper) {
        configurer.componentRegistry(registry -> {
            Converter generalConverter = new JacksonConverter(objectMapper);
            registry.registerComponent(GeneralConverter.class, c -> new DelegatingGeneralConverter(generalConverter));
            registry.registerComponent(MessageConverter.class, c -> new DelegatingMessageConverter(generalConverter));
            registry.registerComponent(EventConverter.class, c -> new DelegatingEventConverter(generalConverter));
        });
    }
    // end::declarative-configuration[]
}
