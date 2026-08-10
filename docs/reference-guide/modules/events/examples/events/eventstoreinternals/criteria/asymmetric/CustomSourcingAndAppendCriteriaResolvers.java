package events.eventstoreinternals.criteria.asymmetric;

// tag::custom-sourcing-and-append-criteria-resolvers[]
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

public class CustomSourcingAndAppendCriteriaResolvers {

    public static class SourcingCriteriaResolver implements CriteriaResolver<AccountId> {

        @Override
        public EventCriteria resolve(AccountId id, ProcessingContext context) {
            return EventCriteria
                    .havingTags(Tag.of("accountId", id.toString()))
                    .andBeingOneOfTypes(AccountCredited.class.getName(), AccountDebited.class.getName());
        }
    }

    public static class AppendCriteriaResolver implements CriteriaResolver<AccountId> {

        @Override
        public EventCriteria resolve(AccountId id, ProcessingContext context) {
            // Only a concurrent debit, never a credit, can invalidate a decision based on the balance.
            return EventCriteria
                    .havingTags(Tag.of("accountId", id.toString()))
                    .andBeingOneOfTypes(AccountDebited.class.getName());
        }
    }
}
// end::custom-sourcing-and-append-criteria-resolvers[]
