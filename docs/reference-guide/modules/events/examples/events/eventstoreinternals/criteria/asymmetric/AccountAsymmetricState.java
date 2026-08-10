package events.eventstoreinternals.criteria.asymmetric;

// tag::account-asymmetric-criteria-imports[]
import org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.SourcingCriteriaBuilder;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

import java.util.Set;
import java.util.stream.Collectors;
// end::account-asymmetric-criteria-imports[]

public class AccountAsymmetricState {

    // tag::narrower-append-criteria[]
    @EventSourcedEntity
    static class NarrowAppendCriteriaState {

        @SourcingCriteriaBuilder
        private static EventCriteria resolveSourcingCriteria(AccountId id) {
            return EventCriteria
                    .havingTags(Tag.of("accountId", id.toString()))
                    .andBeingOneOfTypes(AccountCredited.class.getName(), AccountDebited.class.getName());
        }

        @AppendCriteriaBuilder
        private static EventCriteria resolveAppendCriteria(AccountId id) {
            // Only a concurrent debit, never a credit, can invalidate a decision based on the balance.
            return EventCriteria
                    .havingTags(Tag.of("accountId", id.toString()))
                    .andBeingOneOfTypes(AccountDebited.class.getName());
        }

        // Entity fields and event sourcing handlers omitted...
    }
    // end::narrower-append-criteria[]

    // tag::sourcing-criteria-injection[]
    @EventSourcedEntity
    static class AppendCriteriaFromSourcingCriteriaState {

        @SourcingCriteriaBuilder
        private static EventCriteria resolveSourcingCriteria(AccountId id) {
            return EventCriteria
                    .havingTags(Tag.of("accountId", id.toString()))
                    .andBeingOneOfTypes(AccountCredited.class.getName(), AccountDebited.class.getName());
        }

        @AppendCriteriaBuilder
        private static EventCriteria resolveAppendCriteria(AccountId id, EventCriteria sourcingCriteria) {
            // Narrow to the debit-only type, reusing the exact tags already resolved for sourcing instead
            // of reconstructing them: useful once the sourcing criteria has more than one tag or branch.
            Set<Tag> sourcedTags = sourcingCriteria.flatten().stream()
                                                   .flatMap(criterion -> criterion.tags().stream())
                                                   .collect(Collectors.toSet());
            return EventCriteria.havingTags(sourcedTags).andBeingOneOfTypes(AccountDebited.class.getName());
        }

        // Entity fields and event sourcing handlers omitted...
    }
    // end::sourcing-criteria-injection[]
}

record AccountId(String value) {

    @Override
    public String toString() {
        return value;
    }
}

record AccountCredited(String accountId, long amount) {

}

record AccountDebited(String accountId, long amount) {

}
