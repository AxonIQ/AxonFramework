package root.conversion.implementations.jackson;

import org.axonframework.conversion.Converter;
import org.axonframework.conversion.jackson.JacksonConverter;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

class CustomJacksonConverterExample {

    // tag::custom-jackson-converter[]
    public Converter customJacksonConverter() {
        ObjectMapper customMapper = JsonMapper.builder()
                .findAndAddModules()
                .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .build();

        return new JacksonConverter(customMapper);
    }
    // end::custom-jackson-converter[]
}
