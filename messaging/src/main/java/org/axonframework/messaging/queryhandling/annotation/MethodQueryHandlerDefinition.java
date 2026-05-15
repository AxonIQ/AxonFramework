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

package org.axonframework.messaging.queryhandling.annotation;

import org.axonframework.common.util.ClasspathResolver;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.annotation.HandlerAttributes;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.UnsupportedHandlerException;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.axonframework.common.ReflectionUtils.resolvePrimitiveWrapperTypeIfPrimitive;
import static org.axonframework.common.ReflectionUtils.unwrapIfType;

/**
 * Implementation of a {@link HandlerEnhancerDefinition} used for {@link QueryHandler} annotated methods to wrap a
 * {@link MessageHandlingMember} in a {@link QueryHandlingMember} instance.
 * <p>
 * The {@link QueryHandler#queryName()} is used to define the {@link QueryHandlingMember#queryName()} without any fall
 * back.
 *
 * @author Allard Buijze
 * @since 3.1.0
 */
public class MethodQueryHandlerDefinition implements HandlerEnhancerDefinition {

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
        return original.<String>attribute(HandlerAttributes.QUERY_NAME)
                       .map(queryName -> (MessageHandlingMember<T>) new MethodQueryHandlingMember<>(
                               original, queryName
                       ))
                       .orElse(original);
    }

    private static class MethodQueryHandlingMember<T>
            extends WrappedMessageHandlingMember<T>
            implements QueryHandlingMember<T> {

        private final String queryName;
        private final Type resultType;

        public MethodQueryHandlingMember(MessageHandlingMember<T> original, String queryNameAttribute) {
            super(original);
            queryName = queryNameAttribute;
            resultType = original.unwrap(Method.class)
                                 .map(this::queryResultType)
                                 .orElseThrow(() -> new UnsupportedHandlerException(
                                         "@QueryHandler annotation can only be put on methods.",
                                         original.unwrap(Member.class).orElse(null)
                                 ));
            if (Void.TYPE.equals(resultType)) {
                throw new UnsupportedHandlerException(
                        "@QueryHandler annotated methods must not declare void return type",
                        original.unwrap(Member.class).orElse(null)
                );
            }
        }

        @Override
        public Object handleSync(Message message,
                                 ProcessingContext context,
                                 @Nullable T target) throws Exception {
            Object result = super.handleSync(message, context, target);
            if (result instanceof Optional) {
                return ((Optional<?>) result).orElse(null);
            }
            return result;
        }

        private Type queryResultType(Method method) {
            if (Void.class.equals(method.getReturnType())) {
                throw new UnsupportedHandlerException(
                        "@QueryHandler annotated methods must not declare void return type", method
                );
            }
            return unwrapType(method.getGenericReturnType());
        }

        private Type unwrapType(Type genericReturnType) {
            List<Class<?>> typeToUnwrap = new ArrayList<>(List.of(
                    Future.class, Optional.class, Publisher.class, Iterable.class, Stream.class
            ));
            if (ClasspathResolver.projectReactorOnClasspath()) {
                typeToUnwrap.addAll(List.of(Flux.class, Mono.class));
            }
            return upperBound(resolvePrimitiveWrapperTypeIfPrimitive(
                    unwrapIfType(genericReturnType,
                                 typeToUnwrap.toArray(new Class<?>[0]))
            ));
        }

        private Type upperBound(Type type) {
            if (type instanceof WildcardType) {
                if (((WildcardType) type).getUpperBounds().length == 1) {
                    return ((WildcardType) type).getUpperBounds()[0];
                }
                return Object.class;
            }
            return type;
        }

        @Override
        public boolean canHandle(Message message, ProcessingContext context) {
            return super.canHandle(message, context) && message instanceof QueryMessage;
        }

        @Override
        public String queryName() {
            return queryName;
        }

        @Override
        public Type resultType() {
            return resultType;
        }
    }
}
