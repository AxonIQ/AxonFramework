package root.conversion.tuning.generics;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.axonframework.conversion.jackson.JacksonConverter;

import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.json.JsonMapper;

class DefaultTypingExample {

    JacksonConverter buildConverterWithDefaultTyping() {
        // tag::default-typing-object-mapper[]
        ObjectMapper mapper = JsonMapper.builder()
            .activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                    .allowIfBaseType(Object.class)
                    .build(),
                DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
            )
            .build();

        JacksonConverter converter = new JacksonConverter(mapper);
        // end::default-typing-object-mapper[]
        return converter;
    }
}
