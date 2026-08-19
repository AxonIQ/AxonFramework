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

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

// tag::spring-boot-query-service[]
@Service
public class ItemService {

    private final QueryGateway queryGateway;

    public ItemService(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    public CompletableFuture<ItemDTO> findItem(String itemId) {
        return queryGateway.query(
            new FetchItemQuery(itemId),
            ItemDTO.class
        );
    }
}
// end::spring-boot-query-service[]
