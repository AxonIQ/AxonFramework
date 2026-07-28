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

package org.axonframework.messaging.core.unitofwork;

import org.axonframework.common.DirectExecutor;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test for {@link UnitOfWorkConfiguration}.
 *
 * @author John Hendrikx
 */
class UnitOfWorkConfigurationTest {
    private final UnitOfWorkConfiguration defaultConfig = UnitOfWorkConfiguration.defaultValues();

    @Test
    void shouldHaveExpectedDefault() {
        assertThat(defaultConfig.allowAsyncProcessing()).isTrue();
        assertThat(defaultConfig.workScheduler()).isInstanceOf(DirectExecutor.class);
        assertThat(defaultConfig.processingLifecycleEnhancers()).isEmpty();
        assertThat(defaultConfig.lifecycleInterceptor()).isNull();

        // check list immutability:
        assertThatThrownBy(() -> defaultConfig.processingLifecycleEnhancers().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void lifecycleInterceptorReplacesAndDoesNotModifyPreviousConfig() {
        ProcessingLifecycleInterceptor interceptor =
                ProcessingLifecycleInterceptor.intercept((context, action) -> action.get());

        UnitOfWorkConfiguration newConfig = defaultConfig.lifecycleInterceptor(interceptor);

        // previous config keeps the (absent) default:
        assertThat(defaultConfig.lifecycleInterceptor()).isNull();
        // new config installs the interceptor as-is (replacement):
        assertThat(newConfig.lifecycleInterceptor()).isSameAs(interceptor);
        // unrelated values are preserved:
        assertThat(newConfig.workScheduler()).isEqualTo(defaultConfig.workScheduler());
        assertThat(newConfig.allowAsyncProcessing()).isEqualTo(defaultConfig.allowAsyncProcessing());
    }

    @Test
    void lifecycleInterceptorReplacesAnAlreadyRegisteredInterceptor() {
        ProcessingLifecycleInterceptor first =
                ProcessingLifecycleInterceptor.intercept((context, action) -> action.get());
        ProcessingLifecycleInterceptor second =
                ProcessingLifecycleInterceptor.intercept((context, action) -> action.get());

        UnitOfWorkConfiguration config = defaultConfig.lifecycleInterceptor(first)
                                                      .lifecycleInterceptor(second);

        // the second interceptor replaces the first outright:
        assertThat(config.lifecycleInterceptor()).isSameAs(second);
    }

    @Test
    void addLifecycleInterceptorChainsWithAnAlreadyRegisteredInterceptor() {
        ProcessingLifecycleInterceptor first =
                ProcessingLifecycleInterceptor.intercept((context, action) -> action.get());
        ProcessingLifecycleInterceptor second =
                ProcessingLifecycleInterceptor.intercept((context, action) -> action.get());

        // on a default config the first added interceptor is installed directly:
        UnitOfWorkConfiguration afterFirst = defaultConfig.addLifecycleInterceptor(first);
        assertThat(afterFirst.lifecycleInterceptor()).isSameAs(first);

        // the second is chained after the first, so it is a distinct composed interceptor:
        UnitOfWorkConfiguration afterSecond = afterFirst.addLifecycleInterceptor(second);
        assertThat(afterSecond.lifecycleInterceptor()).isNotSameAs(first);
        assertThat(afterSecond.lifecycleInterceptor()).isNotSameAs(second);
    }

    @Test
    void forcedSameThreadInvocationPreservesActionInterceptor() {
        ProcessingLifecycleInterceptor interceptor =
                ProcessingLifecycleInterceptor.intercept((context, action) -> action.get());
        UnitOfWorkConfiguration config = defaultConfig.lifecycleInterceptor(interceptor);

        UnitOfWorkConfiguration forced = config.forcedSameThreadInvocation();

        assertThat(forced.allowAsyncProcessing()).isFalse();
        assertThat(forced.lifecycleInterceptor()).isSameAs(config.lifecycleInterceptor());
    }

    @Test
    void registerProcessingLifecycleEnhancerShouldNotModifyPreviousConfigOrInterfereWithOtherValues() {
        UnitOfWorkConfiguration config = UnitOfWorkConfiguration.defaultValues();
        Consumer<ProcessingLifecycle> consumer = pc -> {};

        UnitOfWorkConfiguration newConfig = config.registerProcessingLifecycleEnhancer(consumer);

        // check immutability:
        assertThat(config.allowAsyncProcessing()).isEqualTo(defaultConfig.allowAsyncProcessing());
        assertThat(config.workScheduler()).isEqualTo(defaultConfig.workScheduler());
        assertThat(config.processingLifecycleEnhancers()).isEqualTo(defaultConfig.processingLifecycleEnhancers());

        // check nothing other than expected was changed:
        assertThat(newConfig.allowAsyncProcessing()).isEqualTo(defaultConfig.allowAsyncProcessing());
        assertThat(newConfig.workScheduler()).isEqualTo(defaultConfig.workScheduler());
        assertThat(newConfig.processingLifecycleEnhancers()).containsExactly(consumer);

        // check list immutability:
        assertThatThrownBy(() -> newConfig.processingLifecycleEnhancers().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void forcedSameThreadInvocationShouldRetainRegisteredEnhancers() {
        Consumer<ProcessingLifecycle> consumer = pc -> {};
        UnitOfWorkConfiguration config = defaultConfig.registerProcessingLifecycleEnhancer(consumer);

        UnitOfWorkConfiguration newConfig = config.forcedSameThreadInvocation();

        assertThat(newConfig.allowAsyncProcessing()).isFalse();
        assertThat(newConfig.processingLifecycleEnhancers()).containsExactly(consumer);
    }

}
