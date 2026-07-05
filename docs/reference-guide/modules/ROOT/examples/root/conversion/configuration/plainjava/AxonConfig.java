package root.conversion.configuration.plainjava;

// tag::converter-configuration[]
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;

class AxonConfig {

    public void converterConfiguration(MessagingConfigurer configurer) {

        configurer
                // Register the general converter
                .componentRegistry(cr -> cr.registerComponent(
                        GeneralConverter.class,
                        config -> new DelegatingGeneralConverter(new JacksonConverter())
                ))
                // Register the message converter (wraps the general converter)
                .componentRegistry(cr -> cr.registerComponent(
                        MessageConverter.class,
                        config -> new DelegatingMessageConverter(config.getComponent(GeneralConverter.class))
                ))
                // Register the event converter (wraps the message converter)
                .componentRegistry(cr -> cr.registerComponent(
                        EventConverter.class,
                        config -> new DelegatingEventConverter(config.getComponent(MessageConverter.class))
                ));
    }
}
// end::converter-configuration[]
