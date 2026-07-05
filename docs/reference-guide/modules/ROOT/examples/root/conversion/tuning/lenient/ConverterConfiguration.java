package root.conversion.tuning.lenient;

import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// tag::lenient-jackson-converter[]
public class ConverterConfiguration {

    public GeneralConverter buildConverter() {
        ObjectMapper lenientMapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .findAndAddModules()
                .build();

        return new DelegatingGeneralConverter(new JacksonConverter(lenientMapper));
    }
}
// end::lenient-jackson-converter[]
