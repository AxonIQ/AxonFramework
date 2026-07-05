package queries.querydispatchers;

/**
 * Supporting command used by the issuing-subscription-query sample on the query-dispatchers page.
 */
public record RedeemCardCommand(String cardId, int amount) {
}
