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

package org.axonframework.migration;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Verifies the {@link ConvertCommandHandlerConstructorToCompanionObject} recipe — the Kotlin
 * twin of {@link ConvertCommandHandlerConstructorToStaticMethod}. A Kotlin
 * {@code @CommandHandler constructor(...)} migrates to a {@code companion object} containing
 * a {@code @JvmStatic @CommandHandler fun handle(...)}, matching the JVM-level static shape
 * that AF5's command-creates-entity discovery requires.
 */
class ConvertCommandHandlerConstructorToCompanionObjectTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ConvertCommandHandlerConstructorToCompanionObject())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void rewritesKotlinCommandHandlerConstructorToCompanionObjectFun() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler

                        class Auction {
                            @CommandHandler
                            constructor(cmd: Any) {
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler

                        class Auction {
                            companion object {
                                @JvmStatic
                                @CommandHandler
                                fun handle(cmd: Any) {
                                }
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesKotlinClassWithoutCommandHandlerConstructorAlone() {
        rewriteRun(
                kotlin(
                        """
                        package com.example

                        class Auction {
                            constructor(cmd: Any) {
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesJavaSourcesAlone() {
        // The Java twin (ConvertCommandHandlerConstructorToStaticMethod) handles Java; this
        // recipe must not touch them, even when the constructor is a perfect AF4 shape match.
        rewriteRun(
                org.openrewrite.java.Assertions.java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;
                        class Auction {
                            @CommandHandler
                            public Auction(Object cmd) {}
                        }
                        """
                )
        );
    }
}
