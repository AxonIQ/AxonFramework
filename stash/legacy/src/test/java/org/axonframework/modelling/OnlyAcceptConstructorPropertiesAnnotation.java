/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.modelling;

import com.fasterxml.jackson.annotation.JsonCreator.Mode;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

import java.beans.ConstructorProperties;

/**
 * Internal {@link AnnotationIntrospector} for jackson to test JSON conversion ignoring all creator annotation
 * except for the {@link ConstructorProperties}-annotation.
 *
 * @author JohT
 */
public class OnlyAcceptConstructorPropertiesAnnotation extends JacksonAnnotationIntrospector {

    public static final ObjectMapper attachTo(ObjectMapper objectMapper) {
        return objectMapper.setAnnotationIntrospector(new OnlyAcceptConstructorPropertiesAnnotation());
    }

    @Override
    public Mode findCreatorAnnotation(MapperConfig<?> config, Annotated annotated) {
        return (annotated.hasAnnotation(ConstructorProperties.class)) ? super.findCreatorAnnotation(config,
                                                                                                    annotated) : Mode.DISABLED;
    }
}