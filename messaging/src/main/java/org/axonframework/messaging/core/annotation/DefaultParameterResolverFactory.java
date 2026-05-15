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

package org.axonframework.messaging.core.annotation;

import org.axonframework.common.Priority;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Factory for the default parameter resolvers. This factory is capable for providing parameter resolvers for Message,
 * Metadata and @MetadataValue annotated parameters.
 *
 * @author Allard Buijze
 * @since 2.0.0
 */
@Priority(Priority.LOW)
public class DefaultParameterResolverFactory implements ParameterResolverFactory {

    @Nullable
    @Override
    public ParameterResolver<?> createInstance(Executable executable,
                                               Parameter[] parameters,
                                               int parameterIndex) {
        Class<?> parameterType = parameters[parameterIndex].getType();
        if (Message.class.isAssignableFrom(parameterType)) {
            return new MessageParameterResolver(parameterType);
        }
        Optional<Map<String, Object>> metadataValueAnnotation =
                AnnotationUtils.findAnnotationAttributes(parameters[parameterIndex], MetadataValue.class);
        if (metadataValueAnnotation.isPresent()) {
            return new AnnotatedMetadataParameterResolver(metadataValueAnnotation.get(), parameterType);
        }
        if (Metadata.class.isAssignableFrom(parameterType)) {
            return MetadataParameterResolver.INSTANCE;
        }
        if (parameterIndex == 0) {
            Class<?> payloadType = (Class<?>) AnnotationUtils.findAnnotationAttributes(executable, MessageHandler.class)
                                                             .map(attr -> attr.get("payloadType"))
                                                             .orElse(Object.class);
            if (payloadType.isAssignableFrom(parameterType)) {
                return new PayloadParameterResolver(parameterType);
            }
        }
        return null;
    }

    private static class AnnotatedMetadataParameterResolver implements ParameterResolver<Object> {

        private static final String REQUIRED_PROPERTY = "required";
        private static final String METADATA_VALUE_PROPERTY = "metadataValue";

        private final Map<String, Object> metadataValue;
        private final Class<?> parameterType;

        public AnnotatedMetadataParameterResolver(Map<String, Object> metadataValue, Class<?> parameterType) {
            this.metadataValue = metadataValue;
            this.parameterType = parameterType;
        }

        @Override
        public CompletableFuture<Object> resolveParameterValue(ProcessingContext context) {
            return CompletableFuture.completedFuture(
                    Message.fromContext(context)
                           .metadata()
                           .get(metadataValue.get(METADATA_VALUE_PROPERTY).toString())
            );
        }

        @Override
        public boolean matches(ProcessingContext context) {
            Message message = Message.fromContext(context);
            if (message == null) {
                return false;
            }
            return !(parameterType.isPrimitive() || (boolean) metadataValue.get(REQUIRED_PROPERTY))
                    || (message.metadata().containsKey(metadataValue.get(METADATA_VALUE_PROPERTY).toString())
                    && parameterType.isInstance(message.metadata()
                                                       .get(metadataValue.get(METADATA_VALUE_PROPERTY)
                                                                         .toString()))
            );
        }
    }

    private static final class MetadataParameterResolver implements ParameterResolver<Metadata> {

        private static final MetadataParameterResolver INSTANCE = new MetadataParameterResolver();

        private MetadataParameterResolver() {
        }

        @Override
        public CompletableFuture<Metadata> resolveParameterValue(ProcessingContext context) {
            Message message = Message.fromContext(context);
            return CompletableFuture.completedFuture(message.metadata());
        }

        @Override
        public boolean matches(ProcessingContext context) {
            return Message.fromContext(context) != null;
        }
    }

    private static class MessageParameterResolver implements ParameterResolver<Message> {

        private final Class<?> parameterType;

        public MessageParameterResolver(Class<?> parameterType) {
            this.parameterType = parameterType;
        }

        @Override
        public CompletableFuture<Message> resolveParameterValue(ProcessingContext context) {
            return CompletableFuture.completedFuture(Message.fromContext(context));
        }

        @Override
        public boolean matches(ProcessingContext context) {
            Message message = Message.fromContext(context);
            if (message == null) {
                return false;
            }
            return parameterType.isAssignableFrom(message.getClass());
        }
    }
}
