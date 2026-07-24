/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
