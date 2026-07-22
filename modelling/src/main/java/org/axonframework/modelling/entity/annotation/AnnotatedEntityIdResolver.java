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

package org.axonframework.modelling.entity.annotation;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver;

import java.util.Objects;

/**
 * Implementation of the {@link EntityIdResolver} that converts the payload through the configured
 * {@link MessageConverter} then takes the expected representation of the message handler from the
 * {@link AnnotatedEntityMetamodel}.
 * <p>
 * It will then use the delegate {@link EntityIdResolver} to resolve the id, defaulting to the
 * {@link AnnotationBasedEntityIdResolver}.
 *
 * @param <ID> The type of the identifier to resolve.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 * @deprecated Coupling the id resolver directly to the {@link AnnotatedEntityMetamodel} prevents the
 * {@code EntityMetamodel} from being decorated or replaced, since resolution would break whenever the registered
 * component is not an {@code AnnotatedEntityMetamodel}. Payload conversion to the handler's expected representation
 * is now applied independently of the metamodel component, so annotation-based resolution only needs the plain
 * {@link AnnotationBasedEntityIdResolver}. No direct replacement is required.
 */
@Deprecated(since = "5.3.0")
public class AnnotatedEntityIdResolver<ID> implements EntityIdResolver<ID>, DescribableComponent {

    private final AnnotatedEntityMetamodel<?> metamodel;
    private final MessageConverter converter;
    private final EntityIdResolver<ID> delegate;
    private final Class<ID> idType;

    /**
     * Constructs an {@code AnnotatedEntityMetamodelEntityIdResolver} for the provided
     * {@link AnnotatedEntityMetamodel}.
     *
     * @param metamodel The metamodel that dictates the expected representation of the message.
     * @param idType    The type of the id that will be resolved.
     * @param converter The {@link MessageConverter} to use.
     * @param delegate  The {@link EntityIdResolver} to use on the message after conversion.
     */
    public AnnotatedEntityIdResolver(AnnotatedEntityMetamodel<?> metamodel,
                                     Class<ID> idType,
                                     MessageConverter converter,
                                     EntityIdResolver<ID> delegate) {
        this.idType = Objects.requireNonNull(idType, "The idType should not be null.");
        this.metamodel = Objects.requireNonNull(metamodel, "The metamodel should not be null,");
        this.converter = Objects.requireNonNull(converter, "The converter should not be null.");
        this.delegate = Objects.requireNonNull(delegate, "The delegate should not be null.");
    }

    @Override
    public ID resolve(Message message, ProcessingContext context) throws EntityIdResolutionException {
        Class<?> expectedRepresentation = metamodel.getExpectedRepresentation(message.type().qualifiedName());
        if (expectedRepresentation != null) {
            return delegate.resolve(message.withConvertedPayload(expectedRepresentation, converter), context);
        }
        throw new AxonConfigurationException(
                "No expected representation found for message type [" + message.type().qualifiedName() + "]"
        );
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("converter", converter);
        descriptor.describeProperty("idType", idType);
        descriptor.describeProperty("metaModel", metamodel);
    }
}
