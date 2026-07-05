package root.conversion.implementations.avro;

import org.apache.avro.message.SchemaStore;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.avro.AvroConverter;

class AvroConverterExample {

    // tag::build-avro-converter[]
    public Converter buildAvroConverter(SchemaStore schemaStore) {
        return new AvroConverter(
                schemaStore,
                config -> config  // Customize configuration here
        );
    }
    // end::build-avro-converter[]
}
