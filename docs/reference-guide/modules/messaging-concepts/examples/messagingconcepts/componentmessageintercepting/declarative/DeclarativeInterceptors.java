package messagingconcepts.componentmessageintercepting.declarative;

import messagingconcepts.componentmessageintercepting.AccessDeniedException;
import messagingconcepts.componentmessageintercepting.AuditCommandInterceptor;
import messagingconcepts.componentmessageintercepting.AuditLog;
import messagingconcepts.componentmessageintercepting.AuthorizationCommandInterceptor;
import messagingconcepts.componentmessageintercepting.CardSummaryProjection;
import messagingconcepts.componentmessageintercepting.CourseQueryHandler;
import messagingconcepts.componentmessageintercepting.OrderCommandHandler;
import messagingconcepts.componentmessageintercepting.SecurityContext;
import messagingconcepts.componentmessageintercepting.TenantFilterEventInterceptor;
import messagingconcepts.componentmessageintercepting.Tracer;
import messagingconcepts.componentmessageintercepting.TracingEventInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::command-single-imports[]
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.core.MessageStream;

// end::command-single-imports[]
// tag::query-imports[]
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;

// end::query-imports[]
// tag::event-subscribing-imports[]
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;

// end::event-subscribing-imports[]
public class DeclarativeInterceptors {

    private static final Logger log = LoggerFactory.getLogger(DeclarativeInterceptors.class);
    private final SecurityContext securityContext = new SecurityContext();
    private final String tenantId = "tenant-1";

    void commandSingle() {
        // tag::command-single[]
        CommandHandlingModule.named("orders")
                .commandHandlers()
                .autodetectedCommandHandlingComponent(cfg -> new OrderCommandHandler())
                .intercepted(cfg -> (message, context, chain) -> {
                    log.info("Intercepting command: {}", message.type().qualifiedName());
                    return chain.proceed(message, context);
                })
                .build();
        // end::command-single[]
    }

    void commandMultiple() {
        // tag::command-multiple[]
        CommandHandlingModule.named("orders")
                .commandHandlers()
                .autodetectedCommandHandlingComponent(cfg -> new OrderCommandHandler())
                .intercepted(cfg -> new AuthorizationCommandInterceptor(cfg.getComponent(SecurityContext.class)))
                .intercepted(cfg -> new AuditCommandInterceptor(cfg.getComponent(AuditLog.class)))
                .build();
        // end::command-multiple[]
    }

    void query() {
        // tag::query[]
        QueryHandlingModule.named("courses")
                .queryHandlers()
                .autodetectedQueryHandlingComponent(cfg -> new CourseQueryHandler())
                .intercepted(cfg -> (message, context, chain) -> {
                    if (!securityContext.canQuery()) {
                        return MessageStream.failed(new AccessDeniedException("Query not permitted"));
                    }
                    return chain.proceed(message, context);
                })
                .build();
        // end::query[]
    }

    void eventSubscribing() {
        // tag::event-subscribing[]
        EventProcessorModule
                .subscribing("card-summary")
                .eventHandlingComponents(components -> components
                        .autodetected("card-summary-projection", cfg -> new CardSummaryProjection())
                        .intercepted(cfg -> (message, context, chain) -> {
                            log.info("Handling event: {}", message.type().qualifiedName());
                            return chain.proceed(message, context);
                        })
                )
                .notCustomized();
        // end::event-subscribing[]
    }

    void eventPooled() {
        // tag::event-pooled[]
        EventProcessorModule
                .pooledStreaming("card-summary")
                .eventHandlingComponents(components -> components
                        .autodetected("card-summary-projection", cfg -> new CardSummaryProjection())
                        .intercepted(cfg -> new TracingEventInterceptor(cfg.getComponent(Tracer.class)))
                        .intercepted(cfg -> new TenantFilterEventInterceptor(tenantId))
                )
                .notCustomized();
        // end::event-pooled[]
    }
}
