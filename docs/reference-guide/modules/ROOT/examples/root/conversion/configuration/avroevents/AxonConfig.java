package root.conversion.configuration.avroevents;

// tag::avro-events-configuration[]
import org.apache.avro.message.SchemaStore;
import org.axonframework.conversion.avro.AvroConverter;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;

class AxonConfig {

    public void configureAvroConverterForEvents(MessagingConfigurer configurer,
                                                SchemaStore schemaStore) {
        // Use Avro for event conversion
        configurer.componentRegistry(cr -> cr.registerComponent(
                EventConverter.class,
                config -> {
                    AvroConverter avroConverter = new AvroConverter(
                            schemaStore,
                            cfg -> cfg  // Customize configuration
                    );
                    return new DelegatingEventConverter(avroConverter);
                }
        ));
    }
}
// end::avro-events-configuration[]
