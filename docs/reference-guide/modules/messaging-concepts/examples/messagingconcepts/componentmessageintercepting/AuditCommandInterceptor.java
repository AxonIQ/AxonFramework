package messagingconcepts.componentmessageintercepting;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

public class AuditCommandInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    private final AuditLog auditLog;

    public AuditCommandInterceptor(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage command,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> chain) {
        auditLog.record(command.type().qualifiedName(), null);
        return chain.proceed(command, context);
    }
}
