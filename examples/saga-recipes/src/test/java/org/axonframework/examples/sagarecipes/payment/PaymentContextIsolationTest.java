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

package org.axonframework.examples.sagarecipes.payment;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the boundary the whole module rests on: the payment context can be paid for anything, and the saga is the
 * only component that knows both sides. These rules are cheap to state and easy to break by reflex, which is exactly
 * why they are asserted rather than merely documented.
 */
class PaymentContextIsolationTest {

    private static final String BASE = "org.axonframework.examples.sagarecipes";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);

    @Test
    void paymentContextKnowsNothingAboutRentingOrTheSaga() {
        noClasses().that().resideInAPackage("..sagarecipes.payment..")
                   .should().dependOnClassesThat().resideInAnyPackage("..sagarecipes.rental..",
                                                                      "..sagarecipes.saga..")
                   .because("payment is a generic context: it can be paid for anything, and coupling it to renting "
                                    + "would remove the saga's reason to exist")
                   .check(classes);
    }

    @Test
    void rentalContextKnowsNothingAboutPaymentOrTheSaga() {
        noClasses().that().resideInAPackage("..sagarecipes.rental..")
                   .should().dependOnClassesThat().resideInAnyPackage("..sagarecipes.payment..",
                                                                      "..sagarecipes.saga..")
                   .because("renting does not care how it is paid for; the saga wires the two together")
                   .check(classes);
    }

    /**
     * A dependency rule alone would not catch a plain {@code String rentalId} field smuggled onto a payment event,
     * which is how this boundary is most likely to erode in practice.
     */
    @Test
    void paymentMessagesCarryNoRentalVocabulary() {
        var offenders = classes.stream()
                               .filter(c -> c.getPackageName().startsWith(BASE + ".payment"))
                               .filter(c -> c.isRecord() || c.getSimpleName().endsWith("Tags"))
                               .flatMap(c -> componentNamesOf(c.reflect()).stream()
                                              .filter(PaymentContextIsolationTest::isRentalVocabulary)
                                              .map(name -> c.getSimpleName() + "." + name))
                               .toList();

        assertThat(offenders)
                .describedAs("payment records must not name rental concepts; use the opaque paymentReference instead")
                .isEmpty();
    }

    @Test
    void paymentTagKeysBelongToThePaymentContext() {
        assertThat(PaymentTags.PAYMENT_ID).isEqualTo("paymentId");
        assertThat(PaymentTags.PAYMENT_REFERENCE).isEqualTo("paymentReference");
    }

    private static java.util.List<String> componentNamesOf(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            return java.util.List.of();
        }
        return Arrays.stream(components).map(RecordComponent::getName).toList();
    }

    private static boolean isRentalVocabulary(String name) {
        var lower = name.toLowerCase();
        return lower.contains("rental") || lower.contains("bike") || lower.contains("renter");
    }
}
