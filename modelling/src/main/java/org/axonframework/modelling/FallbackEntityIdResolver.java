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

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Objects;

/**
 * An {@link EntityIdResolver} implementation composing two others, a {@code primary} resolver invoked first and a
 * {@code secondary} resolver is invoked <b>only</b> if the {@code primary} fails to resolve an identifier.
 * <p>
 * Useful when a single message type can carry its identifier in more than one way, for example, a
 * {@link org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver} for messages whose payload carries a
 * {@link org.axonframework.modelling.annotation.TargetEntityId}-annotated member, falling back to a
 * {@link MetadataEntityIdResolver} for messages that do not.
 *
 * @param <ID> the type of the identifier to resolve
 * @author Steven van Beelen
 * @see MetadataEntityIdResolver
 * @since 5.4.0
 */
public class FallbackEntityIdResolver<ID> implements EntityIdResolver<ID>, DescribableComponent {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final EntityIdResolver<ID> primary;
    private final EntityIdResolver<ID> secondary;

    /**
     * Initializes the resolver, trying the given {@code primary} resolver before the given {@code secondary} resolver.
     *
     * @param primary   the {@link EntityIdResolver} to resolve the identifier with first
     * @param secondary the {@link EntityIdResolver} to resolve the identifier with when the {@code primary} fails
     */
    public FallbackEntityIdResolver(EntityIdResolver<ID> primary,
                                    EntityIdResolver<ID> secondary) {
        this.primary = Objects.requireNonNull(primary, "The primary EntityIdResolver may not be null.");
        this.secondary = Objects.requireNonNull(secondary, "The secondary EntityIdResolver may not be null.");
    }

    @Override
    public ID resolve(
            Message message,
            ProcessingContext context
    ) throws EntityIdResolutionException {
        try {
            return primary.resolve(message, context);
        } catch (EntityIdResolutionException e) {
            logger.debug(
                    "Unable to resolve primary entity id for message [{}]. Falling back to secondary entity id resolver.",
                    message, e
            );
            return secondary.resolve(message, context);
        }
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("primary", primary);
        descriptor.describeProperty("secondary", secondary);
    }
}
