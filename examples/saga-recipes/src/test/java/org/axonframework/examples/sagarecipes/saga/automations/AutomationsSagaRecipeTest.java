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

package org.axonframework.examples.sagarecipes.saga.automations;

import org.axonframework.examples.sagarecipes.saga.SagaRecipeContractTest;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;

/**
 * Runs the shared contract against the recipe with no process class at all.
 * <p>
 * This is the most interesting run of the matrix. The same six scenarios pass, but nothing here is called
 * {@code PaymentSaga}: the behaviour emerges from six independent slices, none of which knows the others exist. It is
 * the strongest evidence the module offers that "saga" names a shape of behaviour rather than a thing that has to be
 * built.
 * <p>
 * Nothing is added below, because there is nothing recipe-specific to assert. Two of the slices keep no state and the
 * rest keep only a read-through lookup, so the observable contract is the whole story.
 */
@AxonSpringBootTest(properties = "saga.recipe=automations")
class AutomationsSagaRecipeTest extends SagaRecipeContractTest {

}
