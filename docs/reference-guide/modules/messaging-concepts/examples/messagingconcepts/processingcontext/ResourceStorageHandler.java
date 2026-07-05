package messagingconcepts.processingcontext;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

class ResourceStorageHandler implements ResourceKeyDefinitions {

    private final EntityManagerFactory entityManagerFactory = new EntityManagerFactory();
    private final DataSource dataSource = new DataSource();

    // tag::storing-retrieving-resources[]
    @CommandHandler
    public void handle(CreateOrderCommand command, ProcessingContext context) {
        // Add or replace resource
        EntityManager em = entityManagerFactory.createEntityManager();
        context.putResource(EM_KEY, em);

        // Get resource
        EntityManager retrieved = context.getResource(EM_KEY);

        // Check if resource exists
        if (context.containsResource(EM_KEY)) {
            // Resource is available
        }

        // Get or create resource (compute if absent)
        Connection conn = context.computeResourceIfAbsent(
                DB_CONN, () -> dataSource.getConnection()
        );

        // Register cleanup
        context.doFinally(ctx -> {
            EntityManager manager = ctx.removeResource(EM_KEY);
            if (manager != null) {
                manager.close();
            }
        });
    }
    // end::storing-retrieving-resources[]
}
