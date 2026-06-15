package org.axonframework.modelling.command.inspection;

import org.axonframework.modelling.command.AggregateRoot;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link AggregateTypeUtils}.
 *
 * @author Steven van Beelen
 */
class AggregateTypeUtilsTest {

    @Test
    void declaredTypeOfDefaultsToSimpleName() {
        assertEquals("SimpleNameType",
                     AggregateTypeUtils.declaredTypeOf(SimpleNameType.class));
    }

    @Test
    void declaredTypeOfHonorsAggregateRootTypeOverride() {
        assertEquals("customType",
                     AggregateTypeUtils.declaredTypeOf(AggregateRootType.class));
    }

    private static class SimpleNameType {

    }

    @AggregateRoot(type = "customType")
    private static class AggregateRootType extends SimpleNameType {

    }
}