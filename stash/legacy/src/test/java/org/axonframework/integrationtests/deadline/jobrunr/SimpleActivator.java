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

package org.axonframework.integrationtests.deadline.jobrunr;

import org.jobrunr.server.JobActivator;

/**
 * When using Spring it will use the context to get the bean, this is a simple activator, just to return the manager
 * instance. This keeps the tests light.
 */
public class SimpleActivator<T> implements JobActivator {

    private final T instance;

    public SimpleActivator(T instance) {
        this.instance = instance;
    }

    @Override
    public <T> T activateJob(Class<T> type) {
        if (type.isAssignableFrom(instance.getClass())) {
            return (T) instance;
        }
        return null;
    }
}
