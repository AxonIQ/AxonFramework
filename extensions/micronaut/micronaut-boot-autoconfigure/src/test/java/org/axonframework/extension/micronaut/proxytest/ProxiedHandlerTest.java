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

package org.axonframework.extension.micronaut.proxytest;

import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
    classes = ProxiedHandlerTest.TestConfig.class,
    properties = {
        "axon.axonserver.enabled=false",
        "axon.eventstorage.jpa.polling-interval=0"
    }
)
@EnableAutoConfiguration
class ProxiedHandlerTest {
    @Inject private ProxiedEventHandler proxiedEventHandler;
    @Inject private ScopeProxiedEventHandler scopeProxiedEventHandler;
    @Inject private ProxiedCommandHandler proxiedCommandHandler;
    @Inject private ScopeProxiedCommandHandler scopeProxiedCommandHandler;
    @Inject private ProxiedQueryHandler proxiedQueryHandler;
    @Inject private ScopeProxiedQueryHandler scopeProxiedQueryHandler;
    @Inject private EventBus eventBus;
    @Inject private CommandGateway commandGateway;
    @Inject private QueryGateway queryGateway;

    @Test
    void proxiedBeansShouldNotBeDuplicated() {
        assertThat(AopUtils.isAopProxy(proxiedEventHandler)).isTrue();
        assertThat(AopUtils.isAopProxy(scopeProxiedEventHandler)).isTrue();
        assertThat(AopUtils.isAopProxy(proxiedCommandHandler)).isTrue();
        assertThat(AopUtils.isAopProxy(scopeProxiedCommandHandler)).isTrue();
        assertThat(AopUtils.isAopProxy(proxiedQueryHandler)).isTrue();
        assertThat(AopUtils.isAopProxy(scopeProxiedQueryHandler)).isTrue();

        eventBus.publish(null, new GenericEventMessage(MessageType.fromString("my-message#1"), "hello"));

        await().untilAsserted(() -> assertThat(ProxiedEventHandler.callCount).isEqualTo(1));
        await().untilAsserted(() -> assertThat(ScopeProxiedEventHandler.callCount).isEqualTo(1));

        commandGateway.sendAndWait(42);
        commandGateway.sendAndWait(42000000000L);

        assertThat(ProxiedCommandHandler.callCount).isEqualTo(1);
        assertThat(ScopeProxiedCommandHandler.callCount).isEqualTo(1);

        assertThat(queryGateway.query(new QueryForProxy(), String.class).join()).isEqualTo("ProxiedQueryHandler");
        assertThat(queryGateway.query(new QueryForScopedProxy(), String.class).join()).isEqualTo("ScopeProxiedQueryHandler");

        assertThat(ProxiedQueryHandler.callCount).isEqualTo(1);
        assertThat(ScopeProxiedQueryHandler.callCount).isEqualTo(1);
    }

    @Configuration
    @ComponentScan(basePackageClasses = ProxiedHandlerTest.class)
    static class TestConfig {
        @Bean
        static MethodValidationPostProcessor mvpp() {
            return new MethodValidationPostProcessor();
        }
    }

    @Validated
    @Component
    static class ProxiedEventHandler {
        static volatile int callCount;

        @EventHandler(eventName = "my-message")
        public void on(@NotBlank String event) {
            callCount++;
        }
    }

    @Validated
    @Component
    @Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)
    static class ScopeProxiedEventHandler {
        static volatile int callCount;

        @EventHandler(eventName = "my-message")
        public void on(@NotBlank String event) {
            callCount++;
        }
    }

    @Validated
    @Component
    static class ProxiedCommandHandler {
        static volatile int callCount;

        @CommandHandler
        public void handle(@Positive Integer cmd) {
            callCount++;
        }
    }

    @Validated
    @Component
    @Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)
    static class ScopeProxiedCommandHandler {
        static volatile int callCount;

        @CommandHandler
        public void handle(@Positive Long cmd) {
            callCount++;
        }
    }

    @Validated
    @Component
    static class ProxiedQueryHandler {
        static volatile int callCount;

        @QueryHandler
        public String handle(@NotNull QueryForProxy cmd) {
            callCount++;
            return "ProxiedQueryHandler";
        }
    }

    @Validated
    @Component
    @Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)
    static class ScopeProxiedQueryHandler {
        static volatile int callCount;

        @QueryHandler
        public String handle(@NotNull QueryForScopedProxy cmd) {
            callCount++;
            return "ScopeProxiedQueryHandler";
        }
    }

    record QueryForProxy() {}
    record QueryForScopedProxy() {}
}
