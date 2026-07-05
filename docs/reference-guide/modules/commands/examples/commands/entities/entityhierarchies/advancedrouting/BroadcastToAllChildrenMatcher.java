package commands.entities.entityhierarchies.advancedrouting;

import java.lang.reflect.Member;

import org.axonframework.modelling.entity.annotation.AnnotatedEntityMetamodel;
import org.axonframework.modelling.entity.annotation.EventTargetMatcherDefinition;
import org.axonframework.modelling.entity.child.EventTargetMatcher;

class BroadcastToAllChildrenMatcher implements EventTargetMatcherDefinition {

    @Override
    public <E> EventTargetMatcher<E> createChildEntityMatcher(AnnotatedEntityMetamodel<E> entity, Member member) {
        // Deliver every event to every child in the collection.
        return (targetEntity, message, processingContext) -> true;
    }
}
