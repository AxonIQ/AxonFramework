package root.conversion.implementations.jackson;

import org.axonframework.conversion.Converter;
import org.axonframework.conversion.jackson.JacksonConverter;

import tools.jackson.dataformat.xml.XmlMapper;

class XmlJacksonConverterExample {

    // tag::xml-jackson-converter[]
    public Converter xmlBasedJacksonConverter() {
        XmlMapper xmlMapper = XmlMapper.builder()
                .findAndAddModules()
                .build();

        return new JacksonConverter(xmlMapper);
    }
    // end::xml-jackson-converter[]
}
