package queries.querydispatchers;

/**
 * Supporting filter used by {@link FetchCardSummariesQuery} on the query-dispatchers page.
 */
public class CardSummaryFilter {

    private final String idStartsWith;

    public CardSummaryFilter(String idStartsWith) {
        this.idStartsWith = idStartsWith;
    }

    public String getIdStartsWith() {
        return idStartsWith;
    }
}
