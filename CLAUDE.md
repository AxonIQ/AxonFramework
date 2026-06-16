# CLAUDE.md

This file provides guidance to AI Agents (Claude Code, Codex, Gemini, Cursor etc.) when working with code in this repository.

## Project Overview

Axon Framework is a framework for building evolutionary, event-driven microservice systems based on Domain-Driven Design (DDD), Command-Query Responsibility Separation (CQRS), and Event Sourcing principles. This is **Axon Framework 5**, a major version under development with significant architectural changes from version 4.

### Core Principles

- **Simplicity First**: Make every change as simple as possible. Impact minimal code.
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards.
- **Minimal Impact**: Changes should only touch what's necessary. Avoid introducing bugs.

### Workflow Orchestration

1.  **Spec-first**: Enter plan mode for non-trivial tasks (3+ steps or architectural decisions).
2.  **Subagent strategy**: Use subagents liberally to keep main context clean. Offload research and parallel analysis. One task per subagent.
3.  **Self-improvement**: After corrections, update the relevant CLAUDE.md or a file under `.claude/rules/`, or introduce a new one. Write rules that prevent the same mistake.
4.  **Verification**: Run tests, check build, suggest user verification. Ask: "Would a staff engineer approve this?"
5.  **Elegance**: For non-trivial changes, pause and ask "is there a more elegant way?" Skip for simple fixes.
6.  **Autonomous bug fixing**: When given a bug report, just fix it. Point at logs/errors, then resolve. Zero hand-holding.

### Key Architectural Principles

- **JDK 21 Base**: Framework requires Java 21. During implementation use Java 21 features like sealed classes.
- **Reactive Support**: Framework supports both reactive and imperative programming styles without enforcing either
- **Modular Design**: Breaking apart monolithic modules into focused, composable components
- **No ThreadLocals**: Internally avoids ThreadLocals, only used at edges for imperative style
- **Composition over Inheritance**: Favors composition patterns throughout the codebase
- **Declarative Configuration**: Moving from annotation-heavy to declarative configuration approaches

## Branching Strategy

| Branch Pattern | Purpose |
|---|---|
| `main` | Next minor or major release. Always contains all commits — patch branch changes flow upward into `main`. |
| `axon-[2-5].[0-12].x` | Patch release branches (e.g., `axon-5.0.x`). Changes to a patch branch are merged upward through higher patch branches and ultimately into `main`. |
| `bug/[issue-number]/[name]` | Bug fixes. Prefix groups all bug branches together; issue number sub-groups related branches. |
| `enhancement/[issue-number]/[name]` | Enhancements to existing features. |
| `feature/[issue-number]/[name]` | New features. |
| `documentation/[issue-number]/[name]` | Documentation-only changes. |
| `dependency-upgrade/[issue-number]/[name]` | Dependency version upgrades. |

## Commit Guidelines
* use a subject line
* separate the subject line from the description by an empty line
* focus on the how and why in the description, not the what
* use the actual issue number at the start of the subject line, for example `[#2343]`
* reference any related issue numbers in the commit message
* avoid including unrelated changes in the same commit

## Build Commands

Maven wrapper is used (`./mvnw`). Key commands:

```bash
# Full build with tests
./mvnw clean verify

# Build skipping tests
./mvnw clean install -DskipTests

# Build with code coverage (JaCoCo)
./mvnw -Dcoverage clean verify

# Build including example modules
./mvnw -Pexamples clean verify

# Build with integration tests
./mvnw -Pintegration-test clean verify

# Run all unit tests
./mvnw clean test

# Run a single test class
./mvnw test -pl messaging -Dtest=SimpleCommandBusTest

# Run a single test method
./mvnw test -pl messaging -Dtest=SimpleCommandBusTest#methodName

# Run integration tests for a specific module
./mvnw -Pintegration-test verify -pl integrationtests -Dtest=ClassName
```

## Test Guidelines
- When writing tests avoid Mock whenever possible, try to use simplest implementation. Or use some recording implementations if needed. Ask me if it's available or you need to create your own, if you cannot find. Always ask me before doing into Mocking or custom implementation if you don't find existing one.
- **Mockito Spy is justified** when behavior-based assertions alone cannot validate the interaction — e.g., verifying a decorator **prevents** calls to the delegate (`verify(delegate, never())`), or verifying the delegate receives **correct arguments** (`verify(delegate).method(eq(expected))`). Wrap a real implementation with `spy()` and use `verify`/`clearInvocations` as needed. Outside of these cases, prefer state-based assertions.
- **While testing do not focus on implementation details like implemented interfaces, always test the behaviour by API usage.**
- **Test Utilities**: Located in `messaging/src/test/java/org/axonframework/utils/`
- **Additional Rules**:
    - Always use JUnit5, AssertJ (prefer AssertJ assertions over normal JUnit5), Awaitility
    - Use `// given / /when // then` (with spaces between // and the section name) convention with sections separated by such comments
    - **Always create sample events using EventTestUtils**
    - **Always use JUnit5 @Nested classes to group logically connected tests cases (like for same method, same given section etc.)**
    - Do not add @DisplayName for test methods, try to make method names self-explanatory and add meaningful comments in given-when-then sections if needed

Test naming conventions:
- **Unit tests** (Surefire): `*Test.java`, `*Tests.java`, `*TestCase.java`, `Test*.java`
- **Integration tests** (Failsafe): `*IT.java`, `IT*.java`, `*ITCase.java`

## Module Dependency Hierarchy

```
common
  ├── conversion
  │     └── update
  └───────┐
       messaging       ← CommandBus, EventBus, QueryBus, interceptors, annotations. Core messaging infrastructure (commands, events, queries)
          │
       modelling        ← Aggregates, Repositories, EntityMetamodel, StateManagerDomain modeling support (entities)
          │
       eventsourcing    ← EventStore, EventStorageEngine, event sourcing repositories. Event sourcing implementation
          │
       test             ← BDD test fixtures (Given-When-Then DSL)
```

Additional modules:
- `axon-server-connector/` — Distributed communication with Axon Server
- `extensions/spring/` — Spring Boot auto-configuration and starter
- `extensions/metrics/` — Dropwizard and Micrometer metrics
- `extensions/tracing/` — OpenTelemetry distributed tracing
- `stash/legacy*/` — Backward compatibility layers for older Axon versions
- `stash/migration/` — OpenRewrite migration recipes
- `stash/todo/` — Axon Framework 4 parts to be ported to Axon Framework 5
- `integrationtests/` — Cross-module integration test suite
- `build/parent/` — Parent POM with dependency management\
- `examples/` — Example applications for smoke testing and demonstration (build with `-Pexamples`)
- `docs/` — Reference guide and how-to guides written in AsciiDoc, built with Antora

### Examples (`examples/`)

Example applications demonstrating framework features with different setups and stacks:
- `university-demo` — Plain Java example
- `university-java-springboot3` — Spring Boot 3 example
- `university-java-springboot4` — Spring Boot 4 example
- `framework4-springboot4` — Framework 4 compatibility example

When a new feature is implemented, it should be demonstrated in an appropriate example application when possible. This helps validate the feature in a realistic setting and serves as living documentation.

Build examples: `./mvnw -Pexamples clean verify`

### Documentation (`docs/`)

Reference guide and how-to guides at `docs/reference-guide/`, written in AsciiDoc and built with Antora. Additional standalone guides cover topics like dead letter queues, deadlines, identifier generation, meta-annotations, and message handler customization.

After implementing a feature, the relevant documentation in `docs/` should be updated to reflect the changes. See `docs/CLAUDE.md` for detailed documentation migration guidelines (Axon 4 to 5 terminology, style rules, verification workflow).

## Architecture

### Messages Types

Everything flows through three message types, each with its own bus:

| Message Type | Bus | Registry | Handler Interface | Component Interface | Handler Annotation |
|---|---|---|---|---|---|
| `CommandMessage` | `CommandBus` | `CommandHandlerRegistry` | `CommandHandler` | `CommandHandlingComponent` | `@CommandHandler` |
| `EventMessage` | `EventBus` | `EventHandlerRegistry` | `EventHandler` | `EventHandlingComponent` | `@EventHandler` |
| `QueryMessage` | `QueryBus` | `QueryHandlerRegistry` | `QueryHandler` | `QueryHandlingComponent` | `@QueryHandler` |

All messages carry a payload + metadata. Message routing uses `QualifiedName` (derived from payload type or annotation attribute).

Handlers can be registered two ways:
1. **Annotation-based** — classes with `@CommandHandler`/`@EventHandler`/`@QueryHandler` methods, wrapped in `Annotated*HandlingComponent` for discovery and registration.
2. **Programmatic** — implement `CommandHandler`/`EventHandler`/`QueryHandler` (single handler) or `CommandHandlingComponent`/`EventHandlingComponent`/`QueryHandlingComponent` (component with multiple handlers and `supportedCommandNames()`/`supportedEventNames()`/`supportedQueryNames()`) and subscribe directly to the registry.

## Architecture & Design Patterns

### Message-Centric Architecture
- **Messages**: All communication through `CommandMessage`, `EventMessage`, `QueryMessage`
- **Message Buses**: `CommandBus`, `EventBus`, `QueryBus` for message routing
- **Message Handlers**: Components that process messages (aggregates, sagas, projections)
- **Message Interceptors**: Cross-cutting concerns applied to message processing

### Core Abstractions
- **StreamableEventSource**: Event streaming abstraction
- **TrackingToken**: Position tracking in event streams (`GlobalSequenceTrackingToken`)
- **MessageStream**: Reactive stream abstraction for message consumption
- **Context**: Resource management during message processing

### CQRS & Event Sourcing
- **Command Side**: Aggregates process commands, emit events
- **Query Side**: Event handlers build read models from events
- **Event Store**: Persistent event storage with streaming capabilities
- **Snapshots**: Aggregate state snapshots for performance

### Framework Evolution (AF4 → AF5)
Major changes include:
- Decoupling message names from payload types
- Separating framework concerns from Spring/JPA dependencies
- Enhanced reactive programming support
- Simplified configuration model
- Process Manager pattern replacing Sagas


### Annotation Processing Pipeline

Handler annotations are meta-annotations on `@MessageHandler`:

```
@MessageHandler (base)
  ├── @CommandHandler  (messageType = CommandMessage.class)
  ├── @EventHandler    (messageType = EventMessage.class)
  │     └── @EventSourcingHandler (for aggregate state evolution)
  └── @QueryHandler    (messageType = QueryMessage.class)
```

Discovery flow:
1. `AnnotatedHandlerInspector` scans class methods for `@MessageHandler` (or meta-annotated)
2. `HandlerDefinition` SPI creates `MessageHandlingMember` instances (ServiceLoader-based, extensible)
3. `HandlerEnhancerDefinition` wraps members to add behavior (tracing, timeouts, etc.)
4. `Annotated*HandlingComponent` wraps handler classes and registers with the bus

### Parameter Resolution

Handler method parameters are resolved via `ParameterResolver` / `ParameterResolverFactory`:
- Payload (first parameter by default)
- `ProcessingContext` injection
- `@MetadataValue` for metadata fields
- `EventAppender` for appending events from command handlers
- Command dispatcher for sending commands
- Custom resolvers via ServiceLoader SPI

Parameter resolution is async-first (`CompletableFuture<T>`).

### Interceptor Chain

Two interception levels:
1. **`MessageDispatchInterceptor`** — before dispatch, no ProcessingContext yet. Can reject or transform messages.
2. **`MessageHandlerInterceptor`** — around handler invocation, with active ProcessingContext. Also available as `@MessageHandlerInterceptor` annotation on methods.

### ProcessingContext (Unit of Work)

`ProcessingContext` extends `ProcessingLifecycle` and manages per-message processing:
- Type-safe resource storage via `ResourceKey<T>`
- Lifecycle phases (start, commit, rollback)
- Transaction management integration
- Supports branching for sub-contexts

### MessageStream

`MessageStream<M>` is the async-first return type for message handling:
- Non-blocking with callback-based notification
- Supports `map`, `filter`, `peek`, `mapMessage` operations
- Static constructors: `fromIterable()`, `fromItems()`

### Event Sourcing

- `EventStorageEngine` — low-level persistence (JPA/JDBC/in-memory)
- `EventStore` — high-level event operations
- `EventSourcingRepository` — reconstructs entities by replaying events
- Events tagged via `TagResolver` for multi-stream filtering
- `AppendCondition` / `SourcingCondition` for consistency control

### Modelling

- `EntityMetamodel` — type-safe entity metadata
- `StateManager` — entity lifecycle and state transitions
- `Repository<E>` — load/save by ID
- Supports polymorphic entity hierarchies

### Test Fixtures (`test/` module)

BDD-style testing:
- `AxonTestFixture` with phases: `AxonTestGiven` → `AxonTestWhen` → `AxonTestThen*`
- Recording buses: `RecordingCommandBus`, `RecordingEventBus`, `RecordingEventStore`
- Hamcrest-based matchers in `test/.../matchers/`

## Code Style & Conventions

- **Java 21** required
- 4-space indentation, 120-character line limit, LF line endings, UTF-8
- IntelliJ code style: import from `axon_code_style.xml` at repo root
- Import threshold: `class_count_to_use_import_on_demand = 99` (effectively: no wildcard imports)
- **ASCII only**: all source files (`.java`, `.adoc`, `.xml`, `.properties`, etc.) must contain only ASCII characters. Never use curly/smart quotes (`"` `"` `'` `'`), em-dashes (`—`), ellipsis (`…`), or any other non-ASCII Unicode. Use straight ASCII equivalents (`"`, `'`, `--` or `,`, `...`).
- **LF line endings only**: never use CR (`\r`) or CRLF (`\r\n`). Configure your editor/IDE to write LF.

### Implementation Guidelines
1. **Discover API essence** - Design for future flexibility over current completeness
2. **Ease of use** - Support bare-bones, declarative, and annotation-based styles
3. **Threading agnostic** - No assumptions about threading model
4. **Dual paradigm support** - Both reactive and imperative styles
5. **Annotation flexibility** - Support both annotation-based and annotation-less design

### Nullability Annotations
- Use JSpecify `@NullMarked` annotation (`org.jspecify.annotations.NullMarked`) at the package level to express nullability contracts
- By default, all parameters and return values are non-null under `@NullMarked`
- Use JSpecify `@Nullable` annotation (`org.jspecify.annotations.Nullable`) to explicitly mark a parameter or return value as nullable
- Jakarta `@Nonnull` and `@Nullable` annotations are explicitly **forbidden** via `build/checkstyle.xml`
- In implementations, enforce non-null parameters with `Objects.requireNonNull` checks


### Javadoc Guidelines

**Core Writing Conventions:**
1. **Original Style**: Always use original recommended javadoc style.
2. **Fragment Style for Tags**: `@param`, `@return`, and `@throws` tags use **fragment style** — start with a lowercase
   letter, no trailing period. This applies even if surrounding existing code uses sentence style — new code must always
   use fragment style. Class-level and method-level description paragraphs remain normal sentences (capitalized, with
   periods).
    ```java
    // CORRECT — fragment style (lowercase, no period)
    @param type          the extension class
    @param <T>           the extension type
    @return the extension instance, never {@code null}
    @throws AxonConfigurationException if the extension cannot be created

    // WRONG — sentence style (capitalized, with period)
    @param type          The extension class.
    @param <T>           The extension type.
    @return The extension instance, never {@code null}.
    @throws AxonConfigurationException If the extension cannot be created.
    ```
3. **Comprehensive Coverage**: Document ALL public methods, constructors, and classes
4. **Internal Methods**: Even `@Internal` constructors should be documented to explain why they're internal

**Linking and References:**

5. **Always Use `@link` Tags**: When mentioning any class or interface, use `@link` tags
    - Example: `{@link org.axonframework.eventhandling.pooled.PooledStreamingEventProcessor}`
    - Use full qualified names for external packages
    - Can use short names for classes in same package: `{@link EventProcessorConfiguration}`
6. **Method References**: Use `@link` for method references
    - Example: `{@link #defaults(BiFunction)}`
    - External method: `{@link org.axonframework.configuration.MessagingConfigurer#eventProcessing(java.util.function.Consumer)}`

**Documentation Structure:**

7. **Class-Level Documentation Should Include**:
    - Purpose and main responsibility
    - Architectural role (composite, delegate, etc.)
    - Main benefits/use cases
    - Framework integration details (who creates it, when, how to access)
    - Complete usage example with proper API calls
    - `@author` and `@since` tags
8. **Method Documentation Should Include**:
    - Clear purpose statement
    - Parameter descriptions with types linked
    - Return value description
    - Usage notes or common patterns
    - Cross-references to related methods

**Internal API Annotation:**

9. **Mark Internal Code with `@Internal`**: Use `@Internal` (`org.axonframework.common.annotation.Internal`) on classes
   and methods that are not part of the public API. This applies to code extracted purely for internal organization (
   e.g., helper classes, implementation details split out for readability). Internal code is still usable but may
   introduce breaking changes in minor/patch releases. Always document *why* the element is internal in its Javadoc.

**Framework-Specific Guidelines:**

10. **Emphasize Integration**: Explain how components fit into the framework
    - Who creates the component
    - How users should access it (don't instantiate directly)
    - Proper usage patterns through framework APIs
11. **Focus on Purpose Over Implementation**:
    - Emphasize the "why" (shared configuration, centralized management)
    - Don't focus on internal class relationships unless relevant to users
12. **Examples Should Be Realistic**:
    - Use proper framework APIs (`MessagingConfigurer.create()`)
    - Show real-world configuration scenarios
    - Include method chaining patterns

**Content Guidelines:**

13. **Explain Defaults and Automation**:
    - Document automatic behaviors (transaction management setup)
    - Explain what the framework provides out-of-the-box
    - Clarify when manual configuration is needed
14. **Cross-Reference Related Components**:
    - Link to sub-modules and delegated components
    - Reference configuration interfaces
    - Point to related framework classes

**Example Template:**
```java
/**
 * Brief description of component purpose and main responsibility.
 * <p>
 * Detailed explanation of architectural role and how it fits in the framework.
 * Main purpose is to [explain key benefit/capability].
 * <p>
 * [Framework integration notes - who creates it, how to access it]
 * <p>
 * Example usage:
 * <pre>{@code
 * // Realistic example using proper framework APIs
 * }</pre>
 *
 * @author [Author Name]
 * @since [version, like 5.1.0]
 */
```