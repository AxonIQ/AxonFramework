package org.axonframework.extension.micronaut.annotation.processor;


import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.AnnotationValue;

import java.lang.annotation.Annotation;

public final class AxonAnnotationMapperUtils {

    public static <T extends Annotation> AnnotationValue<?> makeExecutable(AnnotationValue<T> annotation) {
        return AnnotationValue
                .builder(annotation)
                .stereotype(
                        AnnotationValue
                                .builder(Executable.class)
                                .member("processOnStartup", true)
                                .build()
                )
                .build();
    }
}
