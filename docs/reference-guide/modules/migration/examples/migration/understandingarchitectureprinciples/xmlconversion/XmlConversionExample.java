package migration.understandingarchitectureprinciples.xmlconversion;

import org.axonframework.conversion.jackson.JacksonConverter;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.xml.XmlFactory;

class XmlConversionExample {

    static void configure() {
        // tag::xml-conversion[]
        // Configure Jackson with XML support
        JacksonConverter xmlConverter = new JacksonConverter(
            new ObjectMapper(new XmlFactory())
        );
        // end::xml-conversion[]
    }
}
