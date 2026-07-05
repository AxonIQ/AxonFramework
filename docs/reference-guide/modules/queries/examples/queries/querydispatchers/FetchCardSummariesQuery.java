package queries.querydispatchers;

/**
 * Supporting query used by the subscription-query and streaming-query samples on the query-dispatchers page.
 */
public class FetchCardSummariesQuery {

    private final int offset;
    private final int limit;
    private final CardSummaryFilter filter;

    public FetchCardSummariesQuery(int offset, int limit, CardSummaryFilter filter) {
        this.offset = offset;
        this.limit = limit;
        this.filter = filter;
    }

    public int getOffset() {
        return offset;
    }

    public int getLimit() {
        return limit;
    }

    public CardSummaryFilter getFilter() {
        return filter;
    }
}
