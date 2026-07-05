package queries.querydispatchers;

/**
 * Supporting command used by the issuing-subscription-query sample on the query-dispatchers page.
 */
public record IssueCardCommand(String cardId, int amount) {
}
