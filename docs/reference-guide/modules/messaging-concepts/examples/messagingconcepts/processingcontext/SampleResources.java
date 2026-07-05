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
