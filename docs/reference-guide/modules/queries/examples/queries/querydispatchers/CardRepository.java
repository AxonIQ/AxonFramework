package queries.querydispatchers;

import java.util.List;

/**
 * Supporting repository used by the streaming-query-example sample on the query-dispatchers page.
 */
public interface CardRepository {

    List<CardSummary> findAll();
}
