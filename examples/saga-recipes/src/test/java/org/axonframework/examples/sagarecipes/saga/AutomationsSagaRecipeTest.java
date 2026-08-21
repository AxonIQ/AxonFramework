package org.axonframework.examples.sagarecipes.saga;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@AxonSpringBootTest(properties = "saga.recipe=automations")
class AutomationsSagaRecipeTest extends SagaRecipeContractTest {

    @Autowired
    private AxonConfiguration configuration;

    @Test
    void eventDrivenAutomationSlicesShareOneProcessor() {
        // when
        var processor = configuration.getModuleConfiguration("EventProcessor[rental-payment-automations]");

        // then
        assertThat(processor).isPresent();
        assertThat(processor.orElseThrow().getComponents(EventHandlingComponent.class)).hasSize(5);
    }
}
