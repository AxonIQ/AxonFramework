package org.axonframework.extension.micronaut.annotation.processor;


import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.TypedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;
import org.axonframework.messaging.core.annotation.MessageHandler;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

import java.util.List;

public class AxonQueryHandlerAnnotationMicronautMapper implements TypedAnnotationMapper<MessageHandler> {

    @Override
    public Class<MessageHandler> annotationType() {
        return MessageHandler.class;
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<MessageHandler> annotation, VisitorContext visitorContext) {
        return List.of(AxonAnnotationMapperUtils.makeExecutable(annotation));
    }
}
