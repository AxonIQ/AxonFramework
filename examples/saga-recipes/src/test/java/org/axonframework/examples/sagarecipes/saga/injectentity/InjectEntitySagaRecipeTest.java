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

package org.axonframework.examples.sagarecipes.saga.injectentity;

import org.axonframework.examples.sagarecipes.saga.SagaRecipeContractTest;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;

/**
 * Runs the shared contract against the recipe that keeps no state of its own.
 * <p>
 * Nothing is added here. This recipe writes nothing beyond the commands it dispatches, so there is no private state
 * for a subclass to assert on, and that absence is the recipe's whole argument.
 */
@AxonSpringBootTest(properties = "saga.recipe=injectentity")
class InjectEntitySagaRecipeTest extends SagaRecipeContractTest {

}
