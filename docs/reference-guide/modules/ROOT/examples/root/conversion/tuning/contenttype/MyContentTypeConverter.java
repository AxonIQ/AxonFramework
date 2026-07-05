package root.conversion.tuning.contenttype;

import org.axonframework.conversion.ContentTypeConverter;

// tag::custom-content-type-converter[]
public class MyContentTypeConverter implements ContentTypeConverter<MySourceType, MyTargetType> {

    @Override
    public Class<MySourceType> expectedSourceType() {
        return MySourceType.class;
    }

    @Override
    public Class<MyTargetType> targetType() {
        return MyTargetType.class;
    }

    @Override
    public MyTargetType convert(MySourceType original) {
        // Perform conversion
        return null;
    }
}
// end::custom-content-type-converter[]

class MySourceType {
}

class MyTargetType {
}
