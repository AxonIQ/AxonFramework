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

package org.axonframework.eventsourcing.snapshot;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.DependencyRules;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies architectural rules for this package. This test can be removed if
 * a module level architecture test is introduced.
 * <p>
 * It checks for:
 * <li>Cycles between packages, to keep packages focused on a single purpose
 * and to make it easier to later move packages to new or other modules</li>
 * <li>Hierarchy violations in packages, where more specialized packages
 * refer to more general packages. This rules encourages to keep API
 * and implementation in separate package branches so they may be split into
 * API and implementation modules at a future time.</li>
 *
 * @author John Hendrikx
 */
@AnalyzeClasses(packages = ArchitectureTest.BASE_PACKAGE_NAME)
class ArchitectureTest {
  static final String BASE_PACKAGE_NAME = "org.axonframework.eventsourcing.snapshot";

  @ArchTest
  private final ArchRule packagesShouldBeFreeOfCycles =
      slices().matching("(**)").should().beFreeOfCycles().as("Package Cycles");

  @ArchTest
  private final ArchRule noClassesShouldDependOnUpperPackages =
      FreezingArchRule.freeze(DependencyRules.NO_CLASSES_SHOULD_DEPEND_UPPER_PACKAGES
                                      .as("Package Hierarchy Violations"));


  @Test
  void shouldMatchPackageName() {
    assertThat(MethodHandles.lookup().lookupClass().getPackageName()).isEqualTo(BASE_PACKAGE_NAME);
  }
}
