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

package org.axonframework.extension.micronaut.config;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanContext;
import org.axonframework.common.configuration.ApplicationConfigurerTestSuite;

class MicronautAxonApplicationTest extends ApplicationConfigurerTestSuite<MicronautAxonApplication> {

    BeanContext beanContext = ApplicationContext.builder().eagerBeansEnabled(false).start();

    @Override
    public MicronautAxonApplication createConfigurer() {
        return beanContext.getBean(MicronautAxonApplication.class);
    }

    @Override
    protected void initialize(MicronautAxonApplication testSubject) {
        beanContext.getBean(MicronautComponentRegistry.class).build();
    }

    @Override
    public boolean supportsOverriding() {
        return false;
    }

    @Override
    public boolean supportsComponentFactories() {
        return false;
    }

    @Override
    public boolean doesOwnLifecycleManagement() {
        return false;
    }
}