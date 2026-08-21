package org.axonframework.examples.sagarecipes.saga;

import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.interception.HandlerInterceptorRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicBoolean;

@TestConfiguration
class PreparePaymentFailureTestConfiguration {
    @Bean
    PreparePaymentFailureSwitch preparePaymentFailureSwitch() {
        return new PreparePaymentFailureSwitch();
    }

    @Bean
    ConfigurationEnhancer preparePaymentFailureEnhancer(PreparePaymentFailureSwitch failureSwitch) {
        return registry -> registry.registerDecorator(
                HandlerInterceptorRegistry.class,
                100,
                (configuration, name, delegate) -> delegate.registerCommandInterceptor(ignored ->
                        (message, context, chain) -> {
                            if (message.type().qualifiedName().equals(new QualifiedName(PreparePayment.class))
                                    && failureSwitch.shouldFail()) {
                                return MessageStream.failed(new IllegalStateException("simulated payment outage"));
                            }
                            return chain.proceed(message, context);
                        })
        );
    }

    static class PreparePaymentFailureSwitch {
        private final AtomicBoolean enabled = new AtomicBoolean();
        private final AtomicBoolean failureObserved = new AtomicBoolean();

        void enable() {
            failureObserved.set(false);
            enabled.set(true);
        }

        void disable() {
            enabled.set(false);
        }

        boolean shouldFail() {
            if (!enabled.get()) {
                return false;
            }
            failureObserved.set(true);
            return true;
        }

        boolean failureObserved() {
            return failureObserved.get();
        }
    }
}
