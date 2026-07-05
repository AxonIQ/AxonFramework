package queries.querydispatchers;

import java.util.List;

/**
 * Supporting repository used by the query handler samples on the query-dispatchers page.
 */
public interface ItemRepository {

    List<String> findItems(String criteria);
}
