package org.axonframework.examples.sagarecipes.saga.shared;

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

public class QualifiedNameRoutingSequencingPolicy implements SequencingPolicy<Message> {
    private final Map<QualifiedName, SequencingPolicy<? super Message>> delegates;

    public QualifiedNameRoutingSequencingPolicy(Map<QualifiedName, SequencingPolicy<? super Message>> delegates) {
        this.delegates = Map.copyOf(delegates);
    }

    @Override
    public Optional<Object> sequenceIdentifierFor(Message message, @Nullable ProcessingContext context) {
        var delegate = delegates.get(message.type().qualifiedName());
        return delegate == null ? Optional.empty() : delegate.sequenceIdentifierFor(message, context);
    }
}
