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

package org.axonframework.extension.micronaut;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.axonframework.extension.micronaut.autoconfig.AxonTimeoutAutoConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toSet;

/**
 * We use ArchUnit to ensure that architecture and coding conventions in this module are met.
 * <p/>
 * In particular:
 * <ul>
 *     <li>when we declare @Autoconfiguration beans, they must be listed in META-INF</li>
 *     <li>classes annotated with @Autoconfiguration should be named accordingly</li>
 * </ul>
 */
class ArchUnitTests {

    private static JavaClasses importedClasses;
    private static final String RESOURCE_PATH = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @BeforeAll
    static void beforeAll() {
        importedClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("org.axonframework.extension.springboot");
    }

    @AfterAll
    static void afterAll() {
        importedClasses = null;
    }

    @Test
    void allAutoconfigurationClassesAreListedInMetaInfImports() {

        classes()
                .that()
                .areAnnotatedWith(AutoConfiguration.class)
                .and()
                // TODO: This exception from the rule can be removed with #3959
                .doNotHaveSimpleName(AxonTimeoutAutoConfiguration.class.getSimpleName())
                .should(listedInAutoConfigurationImports())
                .andShould()
                .haveSimpleNameEndingWith("AutoConfiguration")
                .check(importedClasses);
    }

    @Test
    void allClassesSuffixedWithAutoConfigurationAreAnnotated() {
        classes()
                .that()
                .haveSimpleNameEndingWith("AutoConfiguration")
                .and()
                .areNotAssignableTo(MetricsProperties.AutoConfiguration.class)
                .should()
                .beAnnotatedWith(AutoConfiguration.class)
                .check(importedClasses);
    }

    private static ArchCondition<JavaClass> listedInAutoConfigurationImports() {
        var autoConfigurationImports = autoConfigurationImports();
        return new ArchCondition<>(
                "be listed in " + RESOURCE_PATH) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String className = javaClass.getName();
                if (!autoConfigurationImports.contains(className)) {
                    String message = String.format("AutoConfiguration %s is not listed in %s",
                                                   className,
                                                   RESOURCE_PATH);
                    events.add(SimpleConditionEvent.violated(javaClass, message));
                }
            }
        };
    }



    private static Set<String> autoConfigurationImports() {
        try {
            return StreamUtils.copyToString(new ClassPathResource(RESOURCE_PATH).getInputStream(),
                                            UTF_8)
                              .lines().map(String::trim)
                              .filter(s -> !s.isEmpty())
                              .filter(s -> !s.startsWith("#"))
                              .collect(toSet());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
