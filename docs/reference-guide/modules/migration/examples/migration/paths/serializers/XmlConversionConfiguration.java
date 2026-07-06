package migration.paths.serializers;

import org.axonframework.conversion.Converter;
import org.axonframework.conversion.jackson.JacksonConverter;

import tools.jackson.dataformat.xml.XmlMapper;

class XmlConversionConfiguration {

    // tag::xml-conversion[]
    public void configureXmlConversion() {
        XmlMapper xmlMapper = XmlMapper.builder()
                .findAndAddModules()
                .build();
        Converter xmlConverter = new JacksonConverter(xmlMapper);
    }
    // end::xml-conversion[]
}
