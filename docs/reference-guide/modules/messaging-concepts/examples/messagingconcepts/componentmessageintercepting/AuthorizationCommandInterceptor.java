package messagingconcepts.componentmessageintercepting;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

public class AuthorizationCommandInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    private final SecurityContext securityContext;

    public AuthorizationCommandInterceptor(SecurityContext securityContext) {
        this.securityContext = securityContext;
    }

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage command,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> chain) {
        if (!securityContext.isAuthorized(command)) {
            return MessageStream.failed(new AccessDeniedException("Not authorized"));
        }
        return chain.proceed(command, context);
    }
}
