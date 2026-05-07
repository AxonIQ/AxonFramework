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
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies the messaging-module migration: handler annotations move into
 * {@code .annotation.*} subpackages, and {@code EventBus} renames to
 * {@code EventSink}.
 */
class Axon4ToAxon5MessagingTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.Axon4ToAxon5Messaging"));
    }

    @Test
    void renamesCommandHandlerAnnotationIntoAnnotationSubpackage() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;
                        class Foo {
                            @CommandHandler
                            void handle(Object cmd) {}
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

                        class Foo {
                            @CommandHandler
                            void handle(Object cmd) {}
                        }
                        """
                )
        );
    }

    @Test
    void renamesCommandGatewayIntoMessagingNamespace() {
        // CommandGateway is reached by the recursive `ChangePackage` rule from
        // `org.axonframework.commandhandling` → `org.axonframework.messaging.commandhandling`,
        // which preserves the `gateway` subpackage. No dedicated rule needed.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.gateway.CommandGateway;
                        class Foo {
                            CommandGateway gateway;
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
                        class Foo {
                            CommandGateway gateway;
                        }
                        """
                )
        );
    }

    @Test
    void renamesEventBusToEventSink() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.eventhandling.EventBus;
                        class Foo {
                            EventBus bus;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.eventhandling.EventSink;

                        class Foo {
                            EventSink bus;
                        }
                        """
                )
        );
    }

    // ── RemoveTypeArguments: AF5 message types lost their <T> payload parameter ─

    @Test
    void stripsPayloadTypeArgumentFromEventMessage() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.eventhandling.EventMessage;

                        class Foo {
                            EventMessage<String> typed;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.eventhandling.EventMessage;

                        class Foo {
                            EventMessage typed;
                        }
                        """
                )
        );
    }

    @Test
    void stripsWildcardTypeArgumentFromCommandMessageNestedInsideMessageHandlerInterceptor() {
        // Verifies the recipe descends into nested ParameterizedType nodes — the
        // outer MessageHandlerInterceptor stays generic on its M parameter, but
        // the inner CommandMessage<?> gets de-parameterized.
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.commandhandling.CommandMessage;
                        import org.axonframework.messaging.MessageHandlerInterceptor;

                        class Foo implements MessageHandlerInterceptor<CommandMessage<?>> {}
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.commandhandling.CommandMessage;
                        import org.axonframework.messaging.core.MessageHandlerInterceptor;

                        class Foo implements MessageHandlerInterceptor<CommandMessage> {}
                        """
                )
        );
    }

    @Test
    void stripsBoundedWildcardTypeArgumentFromUnitOfWork() {
        // Two cycles expected: cycle 1 strips the type argument, cycle 2 prunes the
        // CommandMessage import that became unused as a result.
        rewriteRun(
                spec -> spec.expectedCyclesThatMakeChanges(2),
                java(
                        """
                        package com.example;

                        import org.axonframework.commandhandling.CommandMessage;
                        import org.axonframework.messaging.unitofwork.UnitOfWork;

                        class Foo {
                            void m(UnitOfWork<? extends CommandMessage<?>> uow) {}
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.core.unitofwork.UnitOfWork;

                        class Foo {
                            void m(UnitOfWork uow) {}
                        }
                        """
                )
        );
    }

    @Test
    void leavesUnrelatedGenericTypesParameterized() {
        // RemoveTypeArguments must only fire on the configured AF5 message types;
        // user generics and standard JDK generics stay intact.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import java.util.List;
                        class Container<T> {
                            List<String> items;
                            Container<Integer> nested;
                        }
                        """
                )
        );
    }

    // ── ChangeMethodName: AF5 dropped `get` prefix and `MetaData` casing ────

    @Test
    void renamesGetIdentifierToIdentifier() {
        // matchOverrides: true means calls on EventMessage instances also rename,
        // even though only the Message-level method pattern is configured.
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.eventhandling.EventMessage;

                        class Foo {
                            String name(EventMessage<?> e) { return e.getIdentifier(); }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.eventhandling.EventMessage;

                        class Foo {
                            String name(EventMessage e) { return e.identifier(); }
                        }
                        """
                )
        );
    }

    @Test
    void renamesGetMetaDataToMetadata() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.messaging.Message;
                        import org.axonframework.messaging.MetaData;

                        class Foo {
                            MetaData get(Message<?> m) { return m.getMetaData(); }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.core.Message;
                        import org.axonframework.messaging.core.Metadata;

                        class Foo {
                            Metadata get(Message m) { return m.metadata(); }
                        }
                        """
                )
        );
    }

    @Test
    void renamesGetPayloadAndGetPayloadTypeOnEventMessageOverride() {
        // EventMessage overrides Message; matchOverrides:true must rewrite
        // call-sites typed against EventMessage as well.
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.eventhandling.EventMessage;

                        class Foo {
                            Object get(EventMessage<?> e) { return e.getPayload(); }
                            Class<?> type(EventMessage<?> e) { return e.getPayloadType(); }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.eventhandling.EventMessage;

                        class Foo {
                            Object get(EventMessage e) { return e.payload(); }
                            Class<?> type(EventMessage e) { return e.payloadType(); }
                        }
                        """
                )
        );
    }

    @Test
    void renamesWithMetaDataAndAndMetaData() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import java.util.Map;
                        import org.axonframework.eventhandling.EventMessage;

                        class Foo {
                            EventMessage<?> chain(EventMessage<?> e, Map<String, ?> md) {
                                return e.withMetaData(md).andMetaData(md);
                            }
                        }
                        """,
                        """
                        package com.example;

                        import java.util.Map;
                        import org.axonframework.messaging.eventhandling.EventMessage;

                        class Foo {
                            EventMessage chain(EventMessage e, Map<String, ?> md) {
                                return e.withMetadata(md).andMetadata(md);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void rewritesSequencingPolicyLambdaToTwoArgOptional() {
        // AF5 changed SequencingPolicy from one-arg/Object-return to
        // two-arg/Optional<Object>-return. This recipe rewrites the common
        // single-param lambda shape; block-body and anonymous-class
        // implementations stay manual.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.eventhandling.EventMessage;
                        import org.axonframework.eventhandling.async.SequencingPolicy;
                        class Cfg {
                            SequencingPolicy<EventMessage<?>> policy() {
                                return e -> e.getMetaData().get("gameId");
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.eventhandling.EventMessage;
                        import org.axonframework.messaging.core.sequencing.SequencingPolicy;

                        import java.util.Optional;

                        class Cfg {
                            SequencingPolicy<EventMessage> policy() {
                                return (e, ctx) -> Optional.ofNullable(e.metadata().get("gameId"));
                            }
                        }
                        """
                )
        );
    }

    @Test
    void renamesGetTimestampOnEventMessage() {
        // getTimestamp is EventMessage-specific (not on Message); needs its own
        // ChangeMethodName rule against the EventMessage type pattern.
        rewriteRun(
                java(
                        """
                        package com.example;

                        import java.time.Instant;
                        import org.axonframework.eventhandling.EventMessage;

                        class Foo {
                            Instant when(EventMessage<?> e) { return e.getTimestamp(); }
                        }
                        """,
                        """
                        package com.example;

                        import java.time.Instant;
                        import org.axonframework.messaging.eventhandling.EventMessage;

                        class Foo {
                            Instant when(EventMessage e) { return e.timestamp(); }
                        }
                        """
                )
        );
    }

    // ── @EventHandler: class-level CommandGateway → method-param CommandDispatcher ──

    @Test
    void swapsCommandGatewayForCommandDispatcherInSingleStatementEventHandler() {
        // Single-statement handler: `commandGateway.sendAndWait(cmd, md);` becomes
        // `return commandDispatcher.send(cmd, md).getResultMessage();`, return type widened to
        // `CompletableFuture<?>`, and the now-orphan field/constructor are removed.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.gateway.CommandGateway;
                        import org.axonframework.eventhandling.EventHandler;
                        class Reactor {
                            private final CommandGateway commandGateway;
                            Reactor(CommandGateway commandGateway) {
                                this.commandGateway = commandGateway;
                            }
                            @EventHandler
                            void on(Object event) {
                                commandGateway.sendAndWait(event);
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                        import org.axonframework.messaging.eventhandling.annotation.EventHandler;

                        import java.util.concurrent.CompletableFuture;

                        class Reactor {
                            @EventHandler
                            CompletableFuture<?> on(Object event, CommandDispatcher commandDispatcher) {
                                return commandDispatcher.send(event).getResultMessage();
                            }
                        }
                        """
                )
        );
    }

    @Test
    void swapsCommandGatewayForCommandDispatcherInTryCatchEventHandler() {
        // Try/catch with one dispatch per branch: each branch's last expression-statement is wrapped
        // in `return ... .getResultMessage();`, return type widened to `CompletableFuture<?>`.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.gateway.CommandGateway;
                        import org.axonframework.eventhandling.EventHandler;
                        class Reactor {
                            private final CommandGateway commandGateway;
                            Reactor(CommandGateway commandGateway) {
                                this.commandGateway = commandGateway;
                            }
                            @EventHandler
                            void on(Object event) {
                                try {
                                    commandGateway.sendAndWait(event);
                                } catch (Exception e) {
                                    commandGateway.sendAndWait("compensate");
                                }
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                        import org.axonframework.messaging.eventhandling.annotation.EventHandler;

                        import java.util.concurrent.CompletableFuture;

                        class Reactor {
                            @EventHandler
                            CompletableFuture<?> on(Object event, CommandDispatcher commandDispatcher) {
                                try {
                                    return commandDispatcher.send(event).getResultMessage();
                                } catch (Exception e) {
                                    return commandDispatcher.send("compensate").getResultMessage();
                                }
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesProjectorWithoutCommandGatewayUntouched() {
        // Pure projector (no CommandGateway field) — recipe should be a no-op.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.eventhandling.EventHandler;
                        class Projection {
                            @EventHandler
                            void on(Object event) {
                                System.out.println(event);
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.eventhandling.annotation.EventHandler;

                        class Projection {
                            @EventHandler
                            void on(Object event) {
                                System.out.println(event);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void migratesHeroesProcessorTryCatchWithMixedAlreadyMigratedState() {
        // Mid-migration state of Heroes' WhenCreatureRecruitedThenAddToArmyProcessor:
        // annotations and the CommandDispatcher parameter were migrated by a prior per-class skill;
        // only the gateway-call rewrite + return-type widen + field cleanup are still pending.
        // The recipe must finish the migration without re-touching the AF5-shaped pieces.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;

                        import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                        import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
                        import org.axonframework.messaging.eventhandling.annotation.EventHandler;

                        class Reactor {
                            private final CommandGateway commandGateway;
                            Reactor(CommandGateway commandGateway) {
                                this.commandGateway = commandGateway;
                            }
                            @EventHandler
                            void react(Object event, CommandDispatcher commandDispatcher) {
                                try {
                                    var cmd = "Add";
                                    commandGateway.sendAndWait(cmd, "md");
                                } catch (Exception e) {
                                    var compensating = "Compensate";
                                    commandGateway.sendAndWait(compensating, "md");
                                }
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                        import org.axonframework.messaging.eventhandling.annotation.EventHandler;

                        import java.util.concurrent.CompletableFuture;

                        class Reactor {
                            @EventHandler
                            CompletableFuture<?> react(Object event, CommandDispatcher commandDispatcher) {
                                try {
                                    var cmd = "Add";
                                    return commandDispatcher.send(cmd, "md").getResultMessage();
                                } catch (Exception e) {
                                    var compensating = "Compensate";
                                    return commandDispatcher.send(compensating, "md").getResultMessage();
                                }
                            }
                        }
                        """
                )
        );
    }

    @Test
    void widensReturnTypeForGuardIfHandlerAlreadyOnCommandDispatcher() {
        // Mid-migration shape from Heroes' WhenWeekStartedThenProclaimWeekSymbolProcessor: the per-class
        // skill already moved the handler onto CommandDispatcher (no CommandGateway field, no field-cleanup
        // needed), but the method still returns void so the dispatcher result is silently discarded. The
        // recipe must:
        //   * widen `void` → `CompletableFuture<?>`,
        //   * `return commandDispatcher.send(...).getResultMessage()` from the if-then branch,
        //   * append `return CompletableFuture.completedFuture(null);` after the if for the no-op false
        //     branch.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;

                        import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                        import org.axonframework.messaging.eventhandling.annotation.EventHandler;

                        class Reactor {
                            @EventHandler
                            void react(Object event, CommandDispatcher commandDispatcher) {
                                if (event != null) {
                                    var command = "Add";
                                    commandDispatcher.send(command);
                                }
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                        import org.axonframework.messaging.eventhandling.annotation.EventHandler;

                        import java.util.concurrent.CompletableFuture;

                        class Reactor {
                            @EventHandler
                            CompletableFuture<?> react(Object event, CommandDispatcher commandDispatcher) {
                                if (event != null) {
                                    var command = "Add";
                                    return commandDispatcher.send(command).getResultMessage();
                                }
                                return CompletableFuture.completedFuture(null);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesNonEventHandlerCallSiteAlone() {
        // CommandGateway used outside any handler (REST controller / scheduler / runner) must keep the
        // class-level field — only the AF4 → AF5 import move applies. The recipe must not touch a
        // method without `@EventHandler`.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.gateway.CommandGateway;
                        class RestController {
                            private final CommandGateway commandGateway;
                            RestController(CommandGateway commandGateway) {
                                this.commandGateway = commandGateway;
                            }
                            void handle(Object cmd) {
                                commandGateway.sendAndWait(cmd);
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
                        class RestController {
                            private final CommandGateway commandGateway;
                            RestController(CommandGateway commandGateway) {
                                this.commandGateway = commandGateway;
                            }
                            void handle(Object cmd) {
                                commandGateway.sendAndWait(cmd);
                            }
                        }
                        """
                )
        );
    }

    // ── MessageDispatchInterceptor moves into messaging.core ────────────────

    @Test
    void relocatesMessageDispatchInterceptorIntoMessagingCore() {
        // AF4 ships `MessageDispatchInterceptor` flat under `org.axonframework.messaging`;
        // AF5 groups the dispatch/handle interception SPI under `org.axonframework.messaging.core`.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.MessageDispatchInterceptor;
                        class Foo {
                            MessageDispatchInterceptor<?> interceptor;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.core.MessageDispatchInterceptor;

                        class Foo {
                            MessageDispatchInterceptor<?> interceptor;
                        }
                        """
                )
        );
    }

    // ── ResponseTypes import cleanup (YAML-level RemoveImport backstop) ─────

    @Test
    void removesUnusedResponseTypesImportEvenWhenNoCallSiteIsRewritten() {
        // The unwrap recipe leaves the file alone (no qualifying call site), but the import is
        // dead — AF5 dropped the `responsetypes` package entirely. The `RemoveUnusedImports`
        // backstop in `axon4-to-axon5-messaging.yml` should clean it up regardless.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.responsetypes.ResponseTypes;
                        class Foo {
                        }
                        """,
                        """
                        package com.example;

                        class Foo {
                        }
                        """
                )
        );
    }

    @Test
    void keepsResponseTypesImportWhenStillReferencedByThreeArgQuery() {
        // The 3-argument `query(String, Object, ResponseType)` form is the LLM-driven skill's
        // fingerprint and must NOT be touched. The import is still referenced via
        // `ResponseTypes.instanceOf(...)` in the body, so `RemoveImport` (non-forced) leaves it.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
                        import org.axonframework.messaging.responsetypes.ResponseTypes;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<String> findOne(Object payload) {
                                return gateway.query("byId", payload, ResponseTypes.instanceOf(String.class));
                            }
                        }
                        """
                )
        );
    }

    // ── EventProcessor lifecycle method rename: shutDown() → shutdown() ─────

    @Test
    void renamesEventProcessorShutDownToShutdown() {
        // Verifies the AF5 camelCase normalisation: an AF4 `eventProcessor.shutDown()` call site
        // is rewritten to `shutdown()` while the receiver type binding is moved into the
        // AF5 `org.axonframework.messaging.eventhandling` namespace by the surrounding
        // package-rename rules.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.eventhandling.EventProcessor;
                        class Lifecycle {
                            void stop(EventProcessor processor) {
                                processor.shutDown();
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.eventhandling.EventProcessor;
                        class Lifecycle {
                            void stop(EventProcessor processor) {
                                processor.shutdown();
                            }
                        }
                        """
                )
        );
    }
}
