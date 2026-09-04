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

package org.axonframework.examples.sagarecipes.saga.shared;

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link SequencingPolicy} that picks a delegate based on the message's {@link QualifiedName}.
 * <p>
 * A saga sequences events of several different types, and each type keeps its correlation identifier in a different
 * property. No single typed policy spans them, so they have to be composed. The framework offers
 * {@code FallbackSequencingPolicy} and {@code HierarchicalSequencingPolicy} for that, but both work by trying one
 * policy after another, which means attempting a conversion per candidate. Two things make that a poor fit here:
 * <ul>
 *     <li>Reading one event type as another is not reliably an error. A lenient converter can produce a record with
 *     null fields instead of throwing, and the result is a silently wrong sequence identifier rather than a
 *     failure.</li>
 *     <li>Every miss costs a conversion.</li>
 * </ul>
 * Routing on the qualified name avoids both. The name is available without deserializing the payload at all,
 * unmapped events cost nothing, and each mapped event is converted exactly once, to its own concrete type.
 * <p>
 * Returning {@link Optional#empty()} for an unmapped name tells the processor that the message carries no sequencing
 * requirement, which is the correct answer for an event this saga does not handle.
 * <p>
 * Nothing about this is specific to the example. Any component handling events from more than one context hits the
 * same problem, and the composition policies the framework ships,
 * {@code FallbackSequencingPolicy} and {@code HierarchicalSequencingPolicy}, both work by attempting a conversion per
 * candidate type. Routing on a name that is available without deserializing avoids that, so this is a candidate for
 * the framework rather than something every application should have to write.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class QualifiedNameRoutingSequencingPolicy implements SequencingPolicy<Message> {

    private final Map<QualifiedName, SequencingPolicy<? super Message>> delegates;

    /**
     * Initializes the policy with the given routing table.
     *
     * @param delegates the policy to apply per qualified name
     */
    protected QualifiedNameRoutingSequencingPolicy(Map<QualifiedName, SequencingPolicy<? super Message>> delegates) {
        this.delegates = Map.copyOf(Objects.requireNonNull(delegates, "Delegates may not be null."));
    }

    @Override
    public Optional<Object> sequenceIdentifierFor(Message message, @Nullable ProcessingContext context) {
        var delegate = delegates.get(message.type().qualifiedName());
        return delegate == null
                ? Optional.empty()
                : delegate.sequenceIdentifierFor(message, context);
    }
}
