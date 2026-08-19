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
package queries.queryhandlers.parameters;

// The import is indented to the depth of the nested method below, so that the
// indent=0 normalization of the include renders both regions flush left.
// tag::dispatch-with-processing-context-import[]
    import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
    import org.axonframework.messaging.core.unitofwork.ProcessingContext;

// end::dispatch-with-processing-context-import[]
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

public class QueryChainingExample {

    private Map<String, OrderDetails> orderStorage;

    // tag::dispatch-with-processing-context[]
    @QueryHandler
    public OrderDetails handle(FetchOrderDetailsQuery query,
                               ProcessingContext context,
                               QueryGateway queryGateway) {
        // Dispatch another query, passing the ProcessingContext for correlation
        CompletableFuture<CustomerInfo> customerInfo =
            queryGateway.query(new FetchCustomerQuery(query.getCustomerId()),
                              CustomerInfo.class,
                              context);

        return orderStorage.get(query.getOrderId());
    }
    // end::dispatch-with-processing-context[]
}

class FetchOrderDetailsQuery {

    private final String orderId;
    private final String customerId;

    FetchOrderDetailsQuery(String orderId, String customerId) {
        this.orderId = orderId;
        this.customerId = customerId;
    }

    String getOrderId() {
        return orderId;
    }

    String getCustomerId() {
        return customerId;
    }
}

class OrderDetails {
}

class CustomerInfo {
}

class FetchCustomerQuery {

    FetchCustomerQuery(String customerId) {
    }
}
