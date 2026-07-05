package root.conversion.implementations.custom;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.conversion.Converter;

import java.lang.reflect.Type;

// tag::custom-converter[]
public class MyCustomConverter implements Converter {

    @Override
    public <T> T convert(Object input, Type targetType) {
        // Perform the conversion
        return null;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("someCustomProperty", "SomeValue");
    }
}
// end::custom-converter[]
