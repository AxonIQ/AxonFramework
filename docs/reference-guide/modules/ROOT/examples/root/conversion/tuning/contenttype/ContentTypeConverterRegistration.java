package root.conversion.tuning.contenttype;

import org.axonframework.conversion.ChainingContentTypeConverter;
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;

import tools.jackson.databind.ObjectMapper;

class ContentTypeConverterRegistration {

    // tag::register-custom-content-type-converter[]
    public GeneralConverter converterWithCustomContentTypeConverter() {
        ChainingContentTypeConverter contentTypeConverter = new ChainingContentTypeConverter();
        contentTypeConverter.registerConverter(new MyContentTypeConverter());

        return new DelegatingGeneralConverter(new JacksonConverter(new ObjectMapper(), contentTypeConverter));
    }
    // end::register-custom-content-type-converter[]
}
