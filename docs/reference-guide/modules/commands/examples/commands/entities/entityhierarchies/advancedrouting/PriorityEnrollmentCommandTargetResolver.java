package commands.entities.entityhierarchies.advancedrouting;

import java.lang.reflect.Member;

import org.axonframework.modelling.entity.annotation.AnnotatedEntityMetamodel;
import org.axonframework.modelling.entity.annotation.CommandTargetResolverDefinition;
import org.axonframework.modelling.entity.child.CommandTargetResolver;

class PriorityEnrollmentCommandTargetResolver implements CommandTargetResolverDefinition {

    @Override
    public <E> CommandTargetResolver<E> createCommandTargetResolver(AnnotatedEntityMetamodel<E> metamodel,
                                                                    Member member) {
        // Custom selection logic: here, simply the first candidate wins.
        return (candidates, message, context) -> candidates.isEmpty() ? null : candidates.get(0);
    }
}
