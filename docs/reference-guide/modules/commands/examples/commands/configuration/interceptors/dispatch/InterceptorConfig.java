package commands.configuration.interceptors.dispatch;

import java.lang.invoke.MethodHandles;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// tag::dispatch-interceptor-spring[]
@Configuration
public class InterceptorConfig {

    private static final Logger logger =
        LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Bean
    public MessageDispatchInterceptor<CommandMessage> loggingInterceptor() {
        return (command, context, interceptorChain) -> {
            logger.info("Dispatching: {}", command.type().name());
            return interceptorChain.proceed(command, context);
        };
    }
}
// end::dispatch-interceptor-spring[]
