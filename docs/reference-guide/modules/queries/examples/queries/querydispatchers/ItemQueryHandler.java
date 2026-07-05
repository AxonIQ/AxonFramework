package queries.querydispatchers;

import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

import java.util.List;

class ItemQueryHandler {

    private ItemRepository repository;

    // tag::fetch-items-query-handler[]
    @QueryHandler // <1>
    public List<String> query(FetchItemsQuery query) { // <2>
        // return the query result based on given criteria
        return repository.findItems(query.getCriteria());
    }
    // end::fetch-items-query-handler[]
}
