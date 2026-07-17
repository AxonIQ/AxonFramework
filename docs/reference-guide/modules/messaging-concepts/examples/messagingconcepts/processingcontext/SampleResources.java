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
package messagingconcepts.processingcontext;

import java.util.concurrent.CompletableFuture;

// Illustrative infrastructure types used by the resource-management snippets. The snippets show
// these types without imports, so they are defined here in the page's own package. They stand in
// for real storage integrations (JPA, JDBC) and deliberately avoid checked exceptions to keep the
// snippets focused.

class EntityManager {

    void close() {
        // Release the underlying persistence resources.
    }
}

class EntityManagerFactory {

    EntityManager createEntityManager() {
        return new EntityManager();
    }
}

class Connection {

    void close() {
        // Return the connection to the pool.
    }
}

class DataSource {

    Connection getConnection() {
        return new Connection();
    }
}

class Transaction {

    CompletableFuture<Void> commitAsync() {
        return CompletableFuture.completedFuture(null);
    }

    void rollback() {
        // Undo any changes made within this transaction.
    }

    void close() {
        // Release the transaction resources.
    }
}

class TransactionManager {

    Transaction beginTransaction() {
        return new Transaction();
    }
}

class NotificationService {

    void sendOrderConfirmation(String orderId) {
        // Send the confirmation to the customer.
    }
}
