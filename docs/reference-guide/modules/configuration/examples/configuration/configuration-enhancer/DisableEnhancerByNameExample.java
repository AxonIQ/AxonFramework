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
package configuration.enhancer;

// tag::disable-enhancer-name-example[]
import org.axonframework.common.configuration.ComponentRegistry;

class DisableEnhancerByNameExample {

    void configure(ComponentRegistry registry) {
        registry.disableEnhancer("org.axonframework.tracing.SomeTracingEnhancer");
    }
}
// end::disable-enhancer-name-example[]
