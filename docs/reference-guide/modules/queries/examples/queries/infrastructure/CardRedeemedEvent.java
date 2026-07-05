package queries.infrastructure;

/**
 * Supporting event used by the query update emitter samples on the infrastructure page.
 */
public record CardRedeemedEvent(String cardId, int amount) {

}
