package queries.querydispatchers;

// tag::fetch-items-query[]
import org.axonframework.messaging.queryhandling.annotation.Query;

@Query(namespace = "inventory", name = "FetchItems")
public class FetchItemsQuery {
    private final String criteria;

    public FetchItemsQuery(String criteria) {
        this.criteria = criteria;
    }

    public String getCriteria() {
        return criteria;
    }
}
// end::fetch-items-query[]
