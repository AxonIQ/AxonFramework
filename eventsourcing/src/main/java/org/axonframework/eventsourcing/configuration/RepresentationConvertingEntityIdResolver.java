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

package org.axonframework.eventsourcing.configuration;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.EntityIdResolver;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;

/**
 * {@link EntityIdResolver} decorator that converts the message payload to the handler's expected type before
 * delegating ID resolution. The expected type is determined per message by the supplied
 * {@code representationProvider}. If no expected type is known for a message, the payload is passed to the
 * delegate as-is.
 *
 * @param <ID> the type of the identifier to resolve
 * @author John Hendrikx
 * @since 5.3.0
 */
class RepresentationConvertingEntityIdResolver<ID> implements EntityIdResolver<ID>, DescribableComponent {

    private final EntityIdResolver<ID> delegate;
    private final Function<QualifiedName, @Nullable Class<?>> representationProvider;
    private final MessageConverter converter;

    RepresentationConvertingEntityIdResolver(
            EntityIdResolver<ID> delegate,
            Function<QualifiedName, @Nullable Class<?>> representationProvider,
            MessageConverter converter
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.representationProvider = Objects.requireNonNull(representationProvider,
                                                             "representationProvider may not be null");
        this.converter = Objects.requireNonNull(converter, "converter may not be null");
    }

    @Override
    public ID resolve(Message message, ProcessingContext context) throws EntityIdResolutionException {
        Class<?> expectedRepresentation = representationProvider.apply(message.type().qualifiedName());
        if (expectedRepresentation != null) {
            return delegate.resolve(message.withConvertedPayload(expectedRepresentation, converter), context);
        }
        return delegate.resolve(message, context);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("representationProvider", representationProvider);
        descriptor.describeProperty("converter", converter);
    }
}
