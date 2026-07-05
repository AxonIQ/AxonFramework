package commands.configuration.interceptors.dispatch;

import java.lang.invoke.MethodHandles;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::dispatch-interceptor-config-api[]
public class AxonConfig {

    private static final Logger logger =
        LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public static void main(String[] args) {
        MessagingConfigurer.create().registerCommandDispatchInterceptor(
                config -> (command, context, interceptorChain) -> {
                    logger.info("Dispatching: {}", command.type().name());
                    return interceptorChain.proceed(command, context);
                }
        );
    }
}
// end::dispatch-interceptor-config-api[]
