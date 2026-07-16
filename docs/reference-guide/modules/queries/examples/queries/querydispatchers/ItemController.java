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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

// tag::item-controller[]
@RestController
public class ItemController {

    private final QueryGateway queryGateway;

    public ItemController(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @GetMapping("/items/{id}")
    public CompletableFuture<ItemDTO> getItem(@PathVariable String id) {
        return queryGateway.query(
            new FetchItemQuery(id),
            ItemDTO.class
            // No ProcessingContext needed when dispatching from HTTP endpoint
        );
    }
}
// end::item-controller[]
