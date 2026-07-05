package queries.queryhandlers.returnvalues;

import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

// tag::primitive-return-type[]
public class PrimitiveQueryHandler {

    @QueryHandler
    public float handle(QueryPrimitive query) {
        return 0.0f;
    }
}
// end::primitive-return-type[]

class QueryPrimitive {
}
