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

package org.axonframework.test.fixture;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.test.FixtureExecutionException;
import org.axonframework.test.matchers.FieldFilter;
import org.axonframework.test.matchers.IgnoreField;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Fixture for testing Axon Framework application. The fixture can be configured to use your whole application
 * configuration or just a portion of that (single module or component). The fixture allows the execution of
 * given-when-then style.
 * <p>
 * <b>Decorator chain architecture and the two-reference design</b>
 * <p>
 * The fixture maintains two separate references for both the command bus and the event infrastructure:
 * <ol>
 *   <li><b>Outermost references</b> ({@code commandBus}, {@code eventSink}) — obtained from the configuration via
 *       {@code configuration.getComponent(...)}. These sit at the top of the decorator chain and are used by the
 *       given-phase and when-phase to <em>dispatch</em> commands and <em>publish</em> events. Dispatching through
 *       the outermost reference ensures that the message traverses all decorators, including dispatch interceptors
 *       that enrich messages with correlation metadata, tracing headers, etc.</li>
 *   <li><b>Innermost recording references</b> ({@code recordingCommandBus}, {@code recordingEventSink}) — created
 *       by {@link MessagesRecordingConfigurationEnhancer} as the innermost decorators
 *       ({@code DECORATION_ORDER = Integer.MIN_VALUE}). These are used by the then-phase to <em>assert</em> on
 *       recorded messages. Because they sit at the bottom of the decorator chain, they capture messages
 *       <em>after</em> all dispatch interceptors have enriched them.</li>
 * </ol>
 * <p>
 * For commands, the decorator chain looks like:
 * <pre>
 *   commandBus (outermost, for dispatching)
 *     → InterceptingCommandBus (applies dispatch interceptors, enriches metadata)
 *       → recordingCommandBus (innermost, captures post-interceptor commands for assertions)
 *         → raw CommandBus implementation
 * </pre>
 * <p>
 * For events, the same pattern applies. The concrete type depends on the configuration:
 * <ul>
 *   <li>With {@code EventSourcingConfigurer} — an {@code EventStore} is present, so the chain is:
 *     <pre>
 *   eventSink (outermost EventStore, for publishing)
 *     → InterceptingEventStore (applies dispatch interceptors)
 *       → RecordingEventStore (innermost, captures post-interceptor events for assertions)
 *         → raw EventStore implementation
 *     </pre>
 *   </li>
 *   <li>With {@code MessagingConfigurer} (no event sourcing) — an {@code EventBus} is present, so the chain is:
 *     <pre>
 *   eventSink (outermost EventBus, for publishing)
 *     → InterceptingEventBus (applies dispatch interceptors)
 *       → RecordingEventBus (innermost, captures post-interceptor events for assertions)
 *         → raw EventBus implementation (e.g. SimpleEventBus)
 *     </pre>
 *   </li>
 * </ul>
 * Both {@code RecordingEventStore} and {@code RecordingEventBus} implement {@link RecordingEventSink}, so they are
 * held uniformly as {@code recordingEventSink} regardless of the event infrastructure variant.
 * <p>
 * <b>Why two references are necessary:</b> If recording were at the outermost position, the recorder would capture
 * the original, un-enriched message (before dispatch interceptors run). By placing recording at the innermost
 * position, the recorder sees the fully enriched message — but we can no longer use the same reference for
 * dispatching, because dispatching through the innermost reference would skip the interceptors. Hence the fixture
 * keeps both: the outermost for dispatching and the innermost for assertions.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 5.0.0
 */
public class AxonTestFixture implements AxonTestPhase.Setup {

    private final AxonConfiguration configuration;
    private final Customization customization;

    /**
     * The outermost {@link CommandBus} from the decorator chain, obtained via
     * {@code configuration.getComponent(CommandBus.class)}. Used by the given-phase and when-phase to dispatch
     * commands so they traverse the full decorator chain, including all registered
     * {@link org.axonframework.messaging.core.MessageDispatchInterceptor MessageDispatchInterceptors}.
     */
    private final CommandBus commandBus;

    /**
     * The outermost {@link EventSink} from the decorator chain, obtained via
     * {@code configuration.getComponent(EventSink.class)}. Used by the given-phase and when-phase to publish events
     * so they traverse the full decorator chain, including all registered event dispatch interceptors.
     * <p>
     * Depending on the configuration, the actual runtime type is either an {@code EventStore} (when using
     * {@code EventSourcingConfigurer}) or an {@code EventBus} (when using {@code MessagingConfigurer}).
     */
    private final EventSink eventSink;

    /**
     * Registry holding the innermost recording decorators created by
     * {@link MessagesRecordingConfigurationEnhancer} at {@code DECORATION_ORDER = Integer.MIN_VALUE}. Contains the
     * {@link RecordingCommandBus} and {@link RecordingEventSink} used by the then-phase for assertions. Because they
     * are the innermost decorators, they capture messages <em>after</em> all dispatch interceptors have enriched them
     * with metadata.
     */
    private final RecordingComponentsRegistry recordings;

    private final MessageTypeResolver messageTypeResolver;
    private final UnitOfWorkFactory unitOfWorkFactory;

    /**
     * Creates a new fixture.
     * <p>
     * All components are resolved from the given {@code configuration}:
     * <ul>
     *   <li>The outermost {@code commandBus} and {@code eventSink} are used for dispatching commands and publishing
     *       events through the full decorator chain.</li>
     *   <li>The innermost {@code recordingCommandBus} and {@code recordingEventSink} are resolved via the
     *       {@link RecordingComponentsRegistry} (populated by {@link MessagesRecordingConfigurationEnhancer}) and
     *       used by the then-phase for assertions.</li>
     * </ul>
     *
     * @param configuration The configuration to obtain components from.
     * @param customization Collection of customizations for this fixture.
     * @see MessagesRecordingConfigurationEnhancer
     */
    public AxonTestFixture(
            AxonConfiguration configuration,
            Customization customization
    ) {
        this.customization = Objects.requireNonNull(customization, "Customization may not be null.");
        this.configuration = Objects.requireNonNull(configuration, "Configuration may not be null.");
        this.commandBus = configuration.getComponent(CommandBus.class);
        this.eventSink = configuration.getComponent(EventSink.class);
        this.recordings = configuration.getOptionalComponent(RecordingComponentsRegistry.class)
                .orElseThrow(() -> new FixtureExecutionException(
                        "RecordingComponentsRegistry not found in Configuration. "
                                + "Ensure MessagesRecordingConfigurationEnhancer is registered."
                ));
        Objects.requireNonNull(recordings.commandBus(),
                "RecordingCommandBus is not available. "
                        + "Ensure CommandBus is resolved before constructing AxonTestFixture.");
        Objects.requireNonNull(recordings.eventSink(),
                "RecordingEventSink is not available. "
                        + "Ensure EventSink is resolved before constructing AxonTestFixture.");
        this.messageTypeResolver = configuration.getComponent(MessageTypeResolver.class);
        this.unitOfWorkFactory = configuration.getComponent(UnitOfWorkFactory.class);
    }

    /**
     * Creates a new fixture.
     *
     * @param configurer The fixture will use the configuration build from the given configurer to obtain components
     *                   needed for test execution.
     * @return A new fixture instance
     */
    public static AxonTestFixture with(ApplicationConfigurer configurer) {
        return with(configurer, c -> c);
    }

    /**
     * Creates a new fixture.
     * <p>
     * Registers a {@link MessagesRecordingConfigurationEnhancer} that places recording decorators at the innermost
     * position of the decorator chain and registers them in a {@link RecordingComponentsRegistry}. The recording
     * instances are resolved from the configuration when the fixture constructor obtains the registry via
     * {@code configuration.getComponent(RecordingComponentsRegistry.class)} and then reads the
     * {@linkplain RecordingComponentsRegistry#commandBus() recordingCommandBus} and
     * {@linkplain RecordingComponentsRegistry#eventSink() recordingEventSink} from it.
     *
     * @param configurer    The fixture will use the configuration build from the given configurer to obtain components
     *                      needed for test execution.
     * @param customization A function that allows to customize the fixture setup.
     * @return A new fixture instance
     */
    public static AxonTestFixture with(
            ApplicationConfigurer configurer,
            UnaryOperator<Customization> customization
    ) {
        Objects.requireNonNull(configurer, "Configurer may not be null");
        Objects.requireNonNull(customization, "Customization may not be null");
        var fixtureConfiguration = customization.apply(new Customization());
        if (!fixtureConfiguration.integrationEnabled()) {
            configurer = configurer.componentRegistry(cr -> cr.disableEnhancer(
                    "io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer"
            ));
            configurer = configurer.componentRegistry(cr -> cr.disableEnhancer(
                    "io.axoniq.framework.postgresql.PostgresqlConfigurationEnhancer"
            ));
        }
        var recordingEnhancer = new MessagesRecordingConfigurationEnhancer();
        var configuration = configurer.componentRegistry(cr -> cr.registerEnhancer(recordingEnhancer))
                                      .start();
        return new AxonTestFixture(configuration, fixtureConfiguration);
    }

    @Override
    public AxonTestPhase.Given given() {
        return new AxonTestGiven(
                configuration,
                customization,
                commandBus,
                eventSink,
                recordings,
                messageTypeResolver,
                unitOfWorkFactory
        );
    }

    @Override
    public AxonTestPhase.When when() {
        return new AxonTestWhen(
                configuration,
                customization,
                commandBus,
                eventSink,
                recordings,
                messageTypeResolver,
                unitOfWorkFactory
        );
    }

    @Override
    public void stop() {
        configuration.shutdown();
    }

    /**
     * Allow customizing the fixture setup, by {@link #integrationEnabled() enabling it as an integration test} or by
     * including {@link #fieldFilters()}.
     * <p>
     * When the {@link #integrationEnabled()} is set to {@code false} (the default), certain
     * {@link org.axonframework.common.configuration.ConfigurationEnhancer ConfigurationEnhancers} will be
     * {@link org.axonframework.common.configuration.ComponentRegistry#disableEnhancer(String) disabled}. An example of
     * this is the {@code AxonServerConfigurationEnhancer}.
     *
     * @param integrationEnabled toggle describing whether this fixture should integrate with infrastructure (e.g. Axon
     *                           Server). Defaults to {@code false}
     * @param fieldFilters       collections of {@link FieldFilter FieldFilters} used to adjust the matchers for
     *                           commands, events, and result messages
     */
    public record Customization(
            boolean integrationEnabled,
            List<FieldFilter> fieldFilters
    ) {

        /**
         * Creates a new instance of {@code Customization}.
         */
        public Customization() {
            this(false, new ArrayList<>());
        }

        /**
         * Registers the given {@code fieldFilter}, which is used to define which {@link java.lang.reflect.Field Fields}
         * are used when comparing objects.
         * <p>
         * This filter is used by the following methods:
         * <ul>
         *     <li>{@link AxonTestPhase.Then.Message#events}</li>
         *     <li>{@link AxonTestPhase.Then.Message#commands}</li>
         *     <li>{@link AxonTestPhase.Then.Command#resultMessagePayload}</li>
         * </ul>
         * <p>
         * If you use custom assertions with methods like {@link AxonTestPhase.Then.Event#eventsSatisfy} or
         * {@link AxonTestPhase.Then.Event#eventsMatch}  this filter is not taken into account.
         * <p/>
         * When multiple filters are registered, a {@code Field} must be accepted by all registered filters in order to be
         * accepted.
         * <p/>
         * By default, all {@code Fields} are included in the comparison.
         *
         * @param fieldFilter the {@code FieldFilter} that defines which fields to include in the comparison
         * @return the current {@code Customization}, for fluent interfacing
         */
        public Customization registerFieldFilter(FieldFilter fieldFilter) {
            List<FieldFilter> fieldFiltersCopy = new ArrayList<>(this.fieldFilters);
            fieldFiltersCopy.add(fieldFilter);
            return new Customization(integrationEnabled, fieldFiltersCopy);
        }

        /**
         * Indicates that a field with given {@code fieldName}, which is declared in given {@code declaringClass} is
         * ignored when performing deep equality checks.
         * <p>
         * This filter is used by the following methods:
         * <ul>
         *     <li>{@link AxonTestPhase.Then.Message#events}</li>
         *     <li>{@link AxonTestPhase.Then.Message#commands}</li>
         *     <li>{@link AxonTestPhase.Then.Command#resultMessagePayload}</li>
         * </ul>
         * <p>
         * If you use custom assertions with methods like {@link AxonTestPhase.Then.Event#eventsSatisfy} or
         * {@link AxonTestPhase.Then.Event#eventsMatch}  this filter is not taken into account.
         *
         * @param declaringClass the class declaring the field
         * @param fieldName      the name of the field
         * @return the current {@code Customization}, for fluent interfacing
         * @throws FixtureExecutionException when no such field is declared
         */
        public Customization registerIgnoredField(Class<?> declaringClass, String fieldName) {
            return registerFieldFilter(new IgnoreField(declaringClass, fieldName));
        }

        /**
         * Configured field filters.
         *
         * @return the list of field filters
         */
        public List<FieldFilter> fieldFilters() {
            return fieldFilters;
        }

        /**
         * Configures the fixture to run as an integration test.
         * <p>
         * This ensures certain
         * {@link org.axonframework.common.configuration.ConfigurationEnhancer ConfigurationEnhancers} that introduce
         * "heavy" infrastructure components (like the {@code AxonServerConfigurationEnhancer}) are kept in the given
         * {@link ApplicationConfigurer}.
         *
         * @return the current {@code Customization}, for fluent interfacing
         */
        public Customization asIntegrationTest() {
            return new Customization(true, fieldFilters);
        }
    }
}
