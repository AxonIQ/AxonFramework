package org.axonframework.examples.sagarecipes.saga;

import org.axonframework.extension.springboot.test.AxonSpringBootTest;

@AxonSpringBootTest(properties = "saga.recipe=automations")
class AutomationsSagaRecipeTest extends SagaRecipeContractTest {
}
