package org.axonframework.examples.sagarecipes;

import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ContextIsolationTest {
    private final com.tngtech.archunit.core.domain.JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.axonframework.examples.sagarecipes");

    @Nested
    class Dependencies {
        @Test
        void paymentContextKnowsNothingAboutRental() {
            noClasses().that().resideInAPackage("..sagarecipes.payment..")
                       .should().dependOnClassesThat().resideInAnyPackage("..sagarecipes.rental..",
                                                                         "..sagarecipes.saga..")
                       .check(classes);
        }

        @Test
        void rentalContextKnowsNothingAboutPayment() {
            noClasses().that().resideInAPackage("..sagarecipes.rental..")
                       .should().dependOnClassesThat().resideInAnyPackage("..sagarecipes.payment..",
                                                                         "..sagarecipes.saga..")
                       .check(classes);
        }
    }

    @Test
    void paymentTypesDeclareNoRentalOrBikeFields() {
        assertThat(classes.stream().filter(type -> type.getPackageName().contains(".payment"))
                          .flatMap(type -> type.getAllFields().stream()).map(JavaField::getName))
                .noneMatch(name -> name.equalsIgnoreCase("rentalId") || name.equalsIgnoreCase("bikeId"));
    }
}
