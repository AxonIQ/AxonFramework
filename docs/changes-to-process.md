# Axon 5 Reference Guide Changes to Process

This document tracks which API changes need to be applied to each reference guide file during the migration from Axon 4 to Axon 5.

## API Changes Available

The following files in `axon-5/` describe the API changes:
- `api-changes.md` - Main API changes document
- `getting-rid-of-aggregatelifecycle.md` - AggregateLifecycle removal
- `getting-rid-of-messagehandlingmember.md` - MessageHandlingMember removal
- `design-principles.md` - Design principles for Axon 5
- `implementation-guidelines.md` - Implementation guidelines
- `migration-baseline.md` - Migration baseline
- `modularity.md` - Modularity changes
- `reactive-native.md` - Reactive native support
- `plan-of-attack-milestone-release.md` - Release plan

---

## ROOT Module

### modules/ROOT/pages/index.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Updated overview to mention Axon Framework 5
- Introduced messaging-centric approach with three core message types (Commands, Events, Queries)
- Mentioned CQRS, Event Sourcing, and Domain-Driven Design support
- Added Axoniq Platform section for monitoring and management
- Updated reference sections table with current modules

### modules/ROOT/pages/modules.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Updated module structure reflecting new organization
- Listed main modules: axon-messaging, axon-modelling, axon-eventsourcing, axon-test, axon-server-connector
- Documented extension modules organization
- Updated dependency coordinates and groupIds
- Added Axon Bill of Materials (BOM) recommendation

### modules/ROOT/pages/serialization.adoc
**Status:** ✅ COMPLETED

**Changes applied:**
- Updated title to "Serialization and Conversion" to reflect both concepts
- Replaced all Serializer references with Converter throughout the document
- Documented MessageConverter and EventConverter types with clear explanations of their roles
- Added comprehensive section on "Understanding message types and conversion" explaining:
  - MessageType vs Java class concept
  - Payload conversion at handling time with detailed examples
  - How different handlers can receive the same message in different representations
  - How this approach reduces upcaster needs
- Updated default from XStream to Jackson with clear statement that JacksonConverter is now the default
- Removed all XStream-specific documentation
- Added XML support section explaining how to use JacksonConverter with XmlMapper
- Updated MessageTypeResolver section (replacing RevisionResolver)
- Replaced all @Revision references with @Command/@Event/@Query annotations
- Updated all code examples to use Axon 5 APIs (Converter instead of Serializer)
- Added section on choosing the right converter
- Maintained sections on lenient deserialization, generic types, and ContentTypeConverters with updated terminology

### modules/ROOT/pages/spring-boot-integration.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Documented Spring Boot Starter integration with auto-configuration
- Updated dependency coordinates (org.axonframework.extensions.spring)
- Documented automatic detection and registration of message handlers
- Explained Axon Server configuration (recommended)
- Documented alternative configurations without Axon Server
- Updated infrastructure configuration section
- Modern Spring Boot 3 compatible examples

### modules/ROOT/pages/upgrading-to-4-7.adoc
**Status:** ✅ REMOVED
**Note:** File removed as expected - A separate Axon 4 to 5 migration guide will be added as a separate task

### modules/ROOT/pages/known-issues-and-workarounds.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Reviewed and updated for Axon 5 context
- Maintained relevant issues (PostgreSQL Large Object Storage, Sequence generation with JPA)
- Updated cross-references to current module structure
- Kept framework-agnostic issues that still apply

---

## Axon Framework Commands Module

### modules/axon-framework-commands/pages/index.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Rewrote introduction with command-centric, messaging-focused approach
- Emphasized that commands express intent to change state (not just modeling constructs)
- Documented command-centric vs modeling-centric shift from Axon 4
- Introduced stateless vs stateful command handler categorization
- Documented entities as optional implementation patterns (not core framework concepts)
- Clarified that entities are tools for organizing stateful command handling logic
- Explained two entity patterns: event-sourced and state-stored
- Updated table of contents to prioritize command-handlers over modeling
- Added "Getting started" section with typical workflow
- Removed outdated Axon 4 video tutorial reference

### modules/axon-framework-commands/pages/command-dispatchers.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Updated CommandBus API (async-native, optional ProcessingContext parameter)
- Documented CommandGateway changes (CommandResult return type, no CommandCallback)
- Introduced CommandDispatcher for in-handler command dispatch (automatically uses current ProcessingContext)
- Documented ProcessingContext usage:
  - Optional when dispatching: provide when available (dispatching from handler)
  - Pass null or use variant without ProcessingContext from HTTP endpoints
  - CommandDispatcher automatically provides it when used in handlers
- Removed AsynchronousCommandBus and DisruptorCommandBus references
- Added comprehensive code examples showing all three dispatch mechanisms
- Added section explaining ProcessingContext usage in command dispatching
- **Added command routing section:**
  - Explained routing key concept and why it matters for consistency
  - Documented @Command annotation's routingKey attribute (replaces @TargetAggregateIdentifier from Axon 4)
  - Documented custom RoutingStrategy configuration
  - Covered built-in strategies (AnnotationRoutingStrategy, MetadataRoutingStrategy)
  - Added examples for Configuration API and Spring Boot configuration
  - Added custom RoutingStrategy implementation example

### modules/axon-framework-commands/pages/command-handlers.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Completely rewrote documentation with Axon 5 concepts and command-centric approach
- **Updated primary categorization: Stateless vs Stateful handlers (not "entity" vs "external")**
  - Stateless handlers: Don't maintain state in handler itself (may access state through services)
  - Stateful handlers: Maintain state in handler itself across invocations
  - Emphasized commands should change conceptual state (not just validate)
- **Documented two paradigms for stateful handlers:**
  - Entity-centric: Command handlers placed directly on entity class
  - Command-centric: Command handlers in separate component, entity passed as parameter (Axon auto-loads)
  - Explained when to use each approach
- **Handler types within stateful handlers:**
  - Creational handlers: Must be static methods (constructors cannot have @CommandHandler in Axon 5)
  - Instance handlers: Instance methods on entity, or methods with entity parameter
  - Static factory methods can return any value (not required to return entity instance like Axon 4)
  - Handlers return what the method returns (no automatic @EntityId extraction like Axon 4)
  - Removed @CreationPolicy (doesn't exist in Axon 5)
  - Updated all examples to use static methods instead of constructors for creational handlers
- **Documented @EntityCreator annotation:**
  - Explained distinction: @CommandHandler creates via commands, @EntityCreator reconstitutes from events
  - Pattern 1: Creation with identifier only (mutable entities, always creates instance)
  - Pattern 2: Creation from origin event (immutable entities, requires event to exist)
  - Pattern 3: Polymorphic entities with static factory method
  - Documented @InjectEntityId for disambiguating identifier from event payload
  - Added IMPORTANT callout emphasizing the two annotations serve different purposes
- **Documented ProcessingContext injection:**
  - Showed ProcessingContext as injectable parameter in handlers
  - Explained it's mandatory on handling side (automatically created by Axon)
  - Demonstrated accessing correlation data and resources
  - Added note about passing ProcessingContext to all components during handling
- **Documented message type concept extensively:**
  - Explained MessageType (QualifiedName + version) as message identifier
  - Showed @Command annotation with namespace, name, version, routingKey attributes
  - Demonstrated payload conversion at handling time with multiple handlers receiving different representations
  - Explained how this reduces need for upcasters (upcasters still needed for event store structural changes)
  - Provided clear examples of same command handled with different Java types
- **Replaced AggregateLifecycle.apply with EventAppender:**
  - Showed EventAppender as injectable parameter in all command handlers
  - Explained EventAppender is ProcessingContext-aware
  - Demonstrated single and multiple event appending
  - Added warning about not storing/reusing EventAppender across invocations
- **Documented creational vs instance command handlers:**
  - Showed @CommandHandler on constructors (creational)
  - Showed @CommandHandler on static factory methods (creational alternative)
  - Showed @CommandHandler on instance methods
  - Explained automatic return of @EntityId value from creational handlers
- **Comprehensive parameter injection documentation:**
  - Listed all injectable parameters (command payload, EventAppender, ProcessingContext, CommandDispatcher, QueryDispatcher, @MetadataValue)
  - Provided complete example with all parameter types
  - Cross-referenced supported-parameters-annotated-handlers.adoc
- **Documented entity command handlers:**
  - Used @EventSourced annotation (Spring) and @EventSourcedEntity (non-Spring)
  - Used @EntityId annotation instead of @AggregateIdentifier
  - Showed proper separation of command handlers (validation/decision) vs event sourcing handlers (state changes)
- **Documented external command handlers:**
  - Explained when to use external handlers
  - Showed EventSourcedRepository usage with ProcessingContext
  - Demonstrated manual entity loading with repository.load(id, context)
  - Showed EventAppender.forContext(context) pattern for external handlers
  - Provided examples for coordinating multiple entities
- **Added explicit handler declaration:**
  - Documented commandName attribute on @CommandHandler
  - Documented payloadType attribute for conversion
  - Explained use cases for explicit declaration
- **Configuration examples:**
  - Spring Boot auto-discovery with @EventSourced and @Component
  - Configuration API with ApplicationConfigurer, ModellingConfigurer, EventSourcingConfigurer
- Added comprehensive code examples throughout
- Added proper xrefs (command-dispatchers.adoc for routing, configuration.adoc, messaging-concepts)
- Used TODO comment for unit-of-work.adoc → processing-context.adoc rename

### modules/axon-framework-commands/pages/configuration.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- **Complete rewrite with command-centric focus**
- **Introduced ApplicationConfigurer hierarchy:**
  - Documented MessagingConfigurer for messaging infrastructure
  - Documented ModellingConfigurer for domain modeling
  - Documented EventSourcingConfigurer for event sourcing
  - Explained the enhance() pattern for wrapping configurers
- **Updated entity configuration:**
  - Changed from @Aggregate to @EventSourced
  - Showed Spring Boot auto-configuration
  - Showed Configuration API with EntityModule
  - Added custom repository configuration examples
- **Updated command handler registration:**
  - Used CommandHandlingModule.named() with annotatedCommandHandlingComponent()
  - Showed proper handler registration for non-entity handlers
  - Both Spring Boot (@Component) and Configuration API examples
- **Documented component registration patterns:**
  - Basic component registration with ComponentBuilder
  - Component replacement (last registration wins)
  - Component decoration with decorators and example LoggingCommandBus
- **Updated interceptor registration:**
  - Dispatch interceptors (before routing)
  - Handler interceptors (after routing)
  - Both Spring Boot (bean-based) and Configuration API (explicit registration)
  - Reference to messaging-concepts for details
- **Added configuration builder pattern:**
  - Showed fluent chaining of configurers
  - Complete example from EventSourcingConfigurer through entities, handlers, interceptors
- **Added configuration enhancers:**
  - Explained ConfigurationEnhancer for advanced scenarios
  - Showed how to register and use enhancers
- All examples use Axon 5 APIs (ProcessingContext, EventAppender, Repository interface, async patterns)

### modules/axon-framework-commands/pages/infrastructure.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- **Complete rewrite with user-centric focus**
- **Added comprehensive Repository section:**
  - Explained why repositories are needed and what they do
  - Documented async-native API (CompletableFuture return types)
  - Explained ManagedEntity concept and its role in lifecycle management
  - Showed load(), loadOrCreate(), persist() methods
  - Documented ProcessingContext requirement
  - Provided examples of using repositories in external command handlers
  - Documented EventSourcedRepository configuration (Spring Boot and Configuration API)
- **Updated CommandBus section:**
  - Removed AsynchronousCommandBus (no longer exists in Axon 5)
  - Removed DisruptorCommandBus (no longer exists in Axon 5)
  - Kept only SimpleCommandBus with updated examples
  - Updated configuration examples to use ApplicationConfigurer instead of Configurer
  - Documented interceptors with xref to messaging-concepts
- **Updated CommandGateway section:**
  - Simplified to focus on practical usage
  - Showed default CommandGateway interface methods (send, sendAndWait, sendAndForget)
  - Provided realistic REST controller example
  - Documented custom gateway creation with method signature behaviors
  - Updated configuration examples for both Spring Boot and Configuration API
- **Added Command Handling Component registration section:**
  - Documented Spring Boot auto-registration with @Component and @EventSourced
  - Showed manual registration with Configuration API
  - Provided examples for both stateless handlers and event-sourced entities
- **Updated Distributed CommandBus section:**
  - Kept AxonServerCommandBus documentation
  - Kept DistributedCommandBus with extension references
  - Updated routing strategy examples with ApplicationConfigurer
- Removed lengthy, implementation-focused content in favor of practical, user-centric documentation
- All code examples use Axon 5 APIs (ProcessingContext, EventAppender, ApplicationConfigurer)

### modules/axon-framework-commands/pages/modeling/aggregate.adoc
**RENAME TO:** `event-sourced-entity.adoc`
**Status:** ⏸️ TEMPORARILY REMOVED FROM NAVIGATION
**Note:** Commented out in nav.adoc - Development team to decide on reinstatement
**Previous status:** Most core content now covered in command-handlers.adoc
**Changes to apply if reinstated:**
- Evaluate if this page is still needed or should be merged/removed
- Most fundamental concepts now in command-handlers.adoc:
  - @EntityCreator annotation (all patterns including polymorphic)
  - EventAppender.append instead of AggregateLifecycle.apply
  - Creational command handlers (static methods)
  - @EventSourced annotation
  - Entity-centric vs command-centric paradigms
- If kept, focus on advanced entity modeling topics:
  - EntityMetamodel details (if needed for advanced use cases)
  - Detailed immutable entity patterns beyond basic examples
  - Advanced declarative modeling features
- Consider consolidating or creating advanced modeling guide instead

### modules/axon-framework-commands/pages/modeling/aggregate-creation-from-another-aggregate.adoc
**RENAME TO:** `entity-creation-from-another-entity.adoc` OR **CONSIDER REMOVAL**
**Status:** ⏸️ TEMPORARILY REMOVED FROM NAVIGATION
**Note:** Commented out in nav.adoc - Development team to decide on reinstatement
**Previous status:** Likely obsolete with command-centric approach
**Changes to apply if reinstated:**
- Evaluate necessity: This pattern (entity creating another entity) may be an anti-pattern in command-centric design
- Alternative: Commands should target specific entities, not have entities create other entities
- If this represents a valid advanced pattern, update terminology and EventAppender usage
- Otherwise, consider removing or consolidating into advanced patterns section

### modules/axon-framework-commands/pages/modeling/aggregate-polymorphism.adoc
**RENAME TO:** `entity-polymorphism.adoc` OR **MERGE INTO command-handlers.adoc**
**Status:** ⏸️ TEMPORARILY REMOVED FROM NAVIGATION
**Note:** Commented out in nav.adoc - Development team to decide on reinstatement
**Previous status:** Basic polymorphism already covered in command-handlers.adoc
**Changes to apply if reinstated:**
- Basic polymorphic @EntityCreator pattern already documented in command-handlers.adoc
- Evaluate if advanced polymorphism details warrant separate page
- If kept as separate page:
  - Update terminology from aggregate to entity
  - Document advanced polymorphic entity metamodel features
  - Update code examples with EventAppender
  - Focus on complex polymorphic scenarios not covered in basics
- Consider merging into command-handlers.adoc or creating "Advanced Entity Patterns" page

### modules/axon-framework-commands/pages/modeling/multi-entity-aggregates.adoc
**RENAME TO:** `entity-hierarchies.adoc` or `child-entities.adoc`
**Status:** ⏸️ TEMPORARILY REMOVED FROM NAVIGATION
**Note:** Commented out in nav.adoc - Development team to decide on reinstatement
**Previous status:** Advanced pattern - keep as separate page
**Changes to apply if reinstated:**
- This represents advanced state management (parent-child entity relationships)
- NOT covered in command-handlers.adoc (appropriately kept separate)
- Update terminology from aggregate to entity throughout
- Document @EntityMember annotation changes:
  - Multiple children of same type support
  - Custom routing for child entities
- Document event routing changes (no longer forwards to all children by default)
- Update all code examples with EventAppender
- Focus on when and why to use entity hierarchies vs separate entities

### modules/axon-framework-commands/pages/modeling/state-stored-aggregates.adoc
**RENAME TO:** `state-stored-entities.adoc`
**Status:** ⏸️ TEMPORARILY REMOVED FROM NAVIGATION
**Note:** Commented out in nav.adoc - Development team to decide on reinstatement
**Previous status:** Alternative to event sourcing - keep as separate advanced page
**Changes to apply if reinstated:**
- This represents alternative state management strategy (state-stored vs event-sourced)
- NOT covered in command-handlers.adoc (appropriately kept separate)
- Update terminology from aggregate to entity throughout
- Document state-stored entity configuration and when to use
- Update Repository usage patterns (different from EventSourcedRepository)
- Explain trade-offs: state-stored vs event-sourced entities
- Update all code examples with modern Axon 5 APIs
- Note: Most users should use event sourcing; this is for specific use cases

---

## Events Module

### modules/events/pages/index.adoc
**Changes to apply:**
- Introduce MessageStream concept
- Document EventBus to EventSink rename
- Overview of async-native event handling
- Introduce Dynamic Consistency Boundary (DCB) concept

### modules/events/pages/event-dispatchers.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Introduced EventSink concept with clear explanation of EventBus → EventSink rename
- Explained EventSink as the publishing/sending side of event distribution
- Clarified EventSink can distribute to internal handlers, processors, stores, AND external systems (Kafka, RabbitMQ)
- Documented EventAppender for entity event dispatching (replaces AggregateLifecycle.apply)
- Updated terminology from Aggregate to Entity throughout
- Documented ProcessingContext usage:
  - Optional when dispatching: provide when available (from within handler) for correlation
  - Pass null or use method without ProcessingContext when dispatching from HTTP endpoints/outside handlers
  - Added examples for both patterns
- Updated to async API (CompletableFuture<Void> returns)
- Documented EventGateway with comprehensive examples:
  - Publishing without ProcessingContext (from HTTP endpoints)
  - Publishing with ProcessingContext (from event handlers)
- Added direct EventSink usage section for advanced scenarios
- Added Configuration section (Spring Boot and Configuration API examples)
- Added Summary table comparing EventAppender, EventGateway, and EventSink
- Verified all xrefs point to existing files (using current filenames until files are renamed)

### modules/events/pages/event-handlers.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- **Documented handler resolution changes**: All matching event handlers are now invoked (not just one per instance)
  - Provided clear example showing multiple handlers for same event
- **Documented ProcessingContext injection**:
  - Explained that ProcessingContext is mandatory and always created by framework on handling side
  - Showed how to inject ProcessingContext as parameter
  - Explained passing ProcessingContext to components for correlation
  - Added examples of accessing metadata and registering lifecycle callbacks
- **Documented message type concept extensively**:
  - Explained MessageType (QualifiedName + version) as event identity, not Java class
  - Documented @Event annotation with namespace, name, and version attributes
  - Showed three ways handlers declare event types: by parameter type, explicit eventName, and payloadType conversion
  - Provided comprehensive example showing same event to three different handlers with different payload representations
  - Explained payload conversion at handling time and how it reduces need for upcasters
  - Clarified when upcasters are still needed (structural changes in event store)
- **Documented parameter injection**:
  - EventAppender (with IMPORTANT note about parameter injection, not field injection)
  - CommandDispatcher (with ProcessingContext-awareness)
  - ProcessingContext
  - Metadata and @MetadataValue
  - Aggregate-specific parameters (@SourceId, @SequenceNumber, @AggregateType)
  - Event processor parameters (TrackingToken, ReplayStatus, @ReplayContext)
  - Complete example showing all parameter types together
- **Documented event handler return values**:
  - Void return type
  - CompletableFuture for async processing
  - MessageStream for advanced scenarios
- **Documented registration**:
  - Spring Boot auto-configuration examples
  - Configuration API examples
- **Added comprehensive best practices section**:
  - Keep handlers focused
  - Use ProcessingContext for correlation
  - Inject dispatchers as parameters, not fields
  - Handle events idempotently
  - Use result objects instead of exceptions for expected failures
- Cross-referenced processing-context.adoc, supported-parameters-annotated-handlers.adoc, and exception-handling.adoc
- All code examples verified against actual Axon 5 APIs

### modules/events/pages/event-versioning.adoc
**Status:** ✅ COMPLETED

**Changes applied:**
- **Added new "Event versioning approach" section** explaining payload conversion as the primary versioning mechanism:
  - Documented payload conversion at handling time with comprehensive examples
  - Explained when payload conversion is sufficient (adding fields, removing fields, renaming, type changes, restructuring)
  - Explained when upcasters are needed (splitting events, merging events, complex transformations, changing event identity)
  - Added example showing EnrichedOrderPlacedEvent receiving additional computed fields from OrderPlacedEvent
  - Referenced xref:ROOT:conversion.adoc for complete conversion details
- **Added IMPORTANT note** that upcasters are not yet available in Axon 5.0 but will be reintroduced in future releases
- **Updated "Event Upcasting" section**:
  - Revised introduction to explain upcasters are for scenarios where payload conversion isn't sufficient
  - Added cross-references to payload conversion sections
  - Added NOTE explaining when to use upcasters vs payload conversion
- **Replaced @Revision with @Event annotation** throughout all examples:
  - Updated ComplaintEvent examples to use @Event(name = "Complaint", version = "1.0")
  - Added "Event version identification" subsection showing @Event annotation with namespace, name, and version fields
- **Updated configuration examples**:
  - Replaced Configurer with MessagingConfigurer in Configuration API example
  - Updated import statement to org.axonframework.messaging.core.configuration.MessagingConfigurer
- **Terminology updates**:
  - Changed "Serializer" to "Converter" in serialization formats note
  - Changed "aggregate" to "entity" in snapshot upcasting section
  - Changed "Streaming Event Processor" to "streaming event processor" (lowercase)
  - Updated SnapshotFilter warning to reference @Event annotation instead of @Revision
  - Updated RevisionSnapshotFilter reference to VersionSnapshotFilter
- **Structured document for clarity**:
  - Payload conversion approach comes first (lines 8-97)
  - Event upcasting section comes second with clear note about future availability (lines 99-481)
  - Custom event transformation section at the end (lines 483-644)
- **Added "Custom event transformation" section**:
  - Documented practical workaround for event transformation by wrapping EventConverter
  - Provided complete example of TransformingEventConverter that converts to JsonNode intermediate representation
  - Showed how to check message type and version using event.type().qualifiedName().name() and event.type().version()
  - Demonstrated transformation logic (adding fields, renaming fields)
  - Included both Configuration API and Spring Boot registration examples
  - Added NOTE explaining this is a bridge solution until full upcasting infrastructure is available
- All code examples verified against Axon 5 APIs:
  - EventConverter.convertPayload() and convertEvent() methods verified
  - MessageType.qualifiedName().name() and version() methods verified
  - EventMessage.type() method verified
  - Converter.convert() method usage verified
  - DelegatingEventConverter and JacksonConverter verified
- Note: Upcaster APIs (SimpleSerializedType, IntermediateEventRepresentation, SingleEventUpcaster, etc.) remain documented but noted as not yet available in 5.0

### modules/events/pages/infrastructure.adoc
**Status:** ✅ COMPLETED

**Changes applied:**
- **Added Dynamic Consistency Boundaries (DCB) documentation section**:
  - Documented event tags with example: `tags.with("customerId", customerId).with("region", "EU")`
  - Explained EventCriteria for querying events by tags
  - Documented ConsistencyMarker for preventing write conflicts
  - Added NOTE comparing DCB vs traditional aggregate-based storage
  - Emphasized that DCB mainly impacts write side (command handling), not read side
- **Removed JDBC Event Storage Engine section**: Replaced with NOTE directing users to external extension
- **Updated JPA Event Storage Engine section**:
  - Documented entity classes: `AggregateEventEntry` and `AggregateSnapshotEntry`
  - Updated persistence.xml example to show correct entity classes
  - Added NOTE clarifying that DCB is not yet supported for JPA-based storage (Axon Server only)
  - Updated concurrency note to explain aggregate identifier + sequence number constraints
  - Fixed PersistenceExceptionTranslator → PersistenceExceptionResolver
- **Updated "Influencing serialization" section to "Configuring event conversion"**:
  - Changed from XStreamSerializer to JacksonConverter as default
  - Updated terminology from Serializer to Converter throughout
  - Added reference to xref:ROOT:conversion.adoc[Conversion]
  - Provided custom ObjectMapper configuration example
  - Changed "Serializing events vs 'the others'" to "Converting events vs other messages"
- **Updated all configuration examples to use MessagingConfigurer**:
  - Axon Server configuration: `MessagingConfigurer.create()` instead of `DefaultConfigurer.defaultConfiguration()`
  - JPA configuration: `configurer.registerEventSink()` with EmbeddedEventStore
  - Mongo configuration: Updated to use MessagingConfigurer and registerEventSink
  - InMemory configuration: Updated to use MessagingConfigurer and registerEventSink
  - Converter configuration: Shows EventConverter registration with DelegatingEventConverter
- **Updated Spring Boot auto-configuration note**: Removed JDBC references, kept only JPA
- **Replaced DomainEventStream reference**: Changed to "transaction" in MongoEventStorageEngine description
- **Updated EventStore introduction**: Changed "from aggregates" to "from entities", "based on aggregate identifier" to "based on given criteria"
- All imports updated to use correct Axon 5 packages

### modules/events/pages/event-processors/index.adoc
**Changes to apply:**
- Remove ProcessingGroup layer documentation
- Document direct Event Handler to Event Processor assignment
- Update configuration API (EventProcessorModule)
- Remove EventProcessingModule/Configurer references

### modules/events/pages/event-processors/dead-letter-queue.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Removed AF4 "not available in 5.0" warning — DLQ is now available in AF5
- Replaced all AF4 configuration examples with AF5 `MessagingConfigurer`/`EventProcessorModule` API
- Added Spring Boot configuration via properties (`axon.eventhandling.processors.<name>.dlq.enabled=true`)
- Added Spring Boot `EventProcessorDefinition` bean examples
- Documented `DeadLetterQueueConfiguration` fluent API (enabled/disabled/factory/enqueuePolicy/clearOnReset/cacheMaxSize)
- Documented `SequencedDeadLetterQueueFactory` custom factory pattern
- Documented defaults cascade (shared → type-specific → instance-specific)
- Documented sequence identifier caching with `CachingSequencedDeadLetterQueue`
- Updated `SequencedDeadLetterProcessor` API to async (`CompletableFuture<Boolean>`)
- Updated dead-letter retrieval to use `Configuration.getModuleConfiguration()` + `getComponents()`
- Added `context` attribute to dead-letter attributes table
- Updated `diagnostics` type from `MetaData` to `Metadata`
- Updated `EnqueuePolicy` examples with AF5 API (`payload()` instead of `getPayload()`)
- Removed MongoDB implementation reference (not yet available in AF5)
- Removed Saga limitation note (Sagas not available in AF5)
- Removed old `EventProcessingConfigurer.registerDeadLetterQueue/registerDeadLetterQueueProvider` API
- Removed old `EventProcessingConfiguration.sequencedDeadLetterProcessor/deadLetterQueue` API

### modules/events/pages/event-processors/streaming.adoc
**CRITICAL CHANGES:**
**Changes to apply:**
- Remove TrackingEventProcessor documentation entirely
- Document PooledStreamingEventProcessor as default and recommended
- Explain threading model differences and benefits
- Document SequencingPolicy configuration (now more important with DCB)
- Document @SequencingPolicy annotation
- Update configuration examples
- Document StreamableEventSource (replaces StreamableMessageSource)
- Update token management (now async with CompletableFuture)

### modules/events/pages/event-processors/subscribing.adoc
**Changes to apply:**
- Update configuration examples
- Document async nature of processing
- Update code examples with ProcessingContext

---

## Queries Module

### modules/queries/pages/index.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Removed scatter-gather query documentation entirely
- Added overview of async-native querying with CompletableFuture and Publisher
- Documented MessageType-based routing (qualified name + version)
- Updated query type list to remove scatter-gather, emphasize point-to-point, subscription, and streaming queries
- Improved section descriptions with Publisher integration details
- Style guide compliance verified

### modules/queries/pages/configuration.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Updated to use MessagingConfigurer API for configuration
- Documented QueryHandlingModule registration approach
- Added comprehensive examples for Configuration API and Spring Boot
- Documented QueryBus configuration (AxonServerQueryBus and SimpleQueryBus)
- Documented QueryGateway configuration with auto-configuration details
- Updated interceptor registration patterns (dispatch and handler interceptors)
- Added complete code examples for both configuration approaches
- Removed scatter-gather query references
- Style guide compliance verified

### modules/queries/pages/implementations.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Updated QueryGateway section to document async-native operations
- Documented query() and queryMany() methods with CompletableFuture
- Documented streamingQuery() with Publisher
- Documented subscriptionQuery() with Publisher combining initial result and updates
- Updated AxonServerQueryBus section with distributed query handling features
- Updated SimpleQueryBus section with local query handling details
- Added comprehensive subscription query infrastructure section:
  - QueryUpdateEmitter with context-aware usage pattern
  - Subscription lifecycle documentation
  - Update buffer configuration and management
  - Code examples for client-side subscription queries with Reactor
- Style guide compliance verified

### modules/queries/pages/query-dispatchers.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Documented removal of scatter-gather query support with link to GitHub issue for feedback
- Documented point-to-point query routing behavior when multiple handlers exist
- Updated QueryGateway API documentation:
  - No more ResponseTypes - use Class types directly
  - query() for single result, queryMany() for list results
  - All methods return CompletableFuture (async-native)
  - Optional ProcessingContext parameter documented
- Documented ProcessingContext usage:
  - Provide when dispatching from within message handlers (for correlation data)
  - Omit or pass null when dispatching from HTTP endpoints/outside handlers
  - Comprehensive examples for both patterns
- Updated subscription query documentation:
  - Returns single Publisher that combines initial results and updates
  - No need to explicitly close subscriptions - cancelling Publisher automatically closes query
  - QueryUpdateEmitter must be injected as handler parameter (not field)
  - QueryUpdateEmitter is ProcessingContext-aware
  - Added clear WRONG vs CORRECT examples
  - Updated code examples to use Publisher API with Flux.from() and dispose()
- Updated streaming query documentation:
  - Documented Publisher-based streaming
  - Native Flux support for fine-grained control
  - Back-pressure, cancellation, and error handling
  - Transaction leaking concerns
- Added Configuration section (Spring Boot and Configuration API examples)
- Added Summary table comparing query types
- **Note:** QueryDispatcher does not exist in Axon 5 - QueryGateway with ProcessingContext is the recommended approach
- Verified all xrefs point to existing files
- Style guide compliance verified (heading capitalization, API/HTTP capitalization)

### modules/queries/pages/query-handlers.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Added overview emphasizing async-native query handling (CompletableFuture, Publisher)
- Documented message type concept and query name resolution:
  - Explained MessageType (QualifiedName + version) as message identifier
  - Documented @Query annotation usage with namespace, name, and version
  - Showed how handlers declare types via @Query annotation or parameter type
  - Explained payload conversion at handling time
  - Demonstrated how different handlers can receive same query in different representations
  - Explained reduction in need for upcasters
- Added comprehensive parameter injection section:
  - ProcessingContext injection (mandatory - always available in handlers)
  - Showed ProcessingContext must be passed to components during handling
  - Documented QueryUpdateEmitter creation from ProcessingContext (context-aware)
  - Added WARNING about QueryUpdateEmitter injection (must use forContext(), not field injection)
  - Documented other parameters (Metadata, @MetadataValue, Message)
- Updated query handler return values:
  - Removed ResponseType references throughout
  - Updated to use query() for single results, queryMany() for multiple results
  - Added CompletableFuture as async return type option
  - Added streaming query return values section (Publisher, Flux)
  - Documented that streaming queries allow efficient handling of large result sets
- Added code examples throughout showing ProcessingContext usage and correlation
- Style guide compliance verified

---

## Sagas Module

**CRITICAL:** Sagas are NOT available in Axon 5

### modules/sagas/pages/index.adoc
**COMPLETE REWRITE:**
**Changes to apply:**
- Add prominent notice that **Sagas are not available in Axon 5**
- Explain that Sagas will be reintroduced in a later version in a different format
- **Document alternatives for Axon 5:**
  1. **Stateful event handlers** - Event handlers that maintain state
  2. **Event handlers with custom state storage** - Store state in appropriate storage (database, cache, etc.)
  3. **Replace deadlines with database checks** - Instead of deadline scheduling, use regular checks on database state
- Provide migration patterns from Axon 4 Sagas to these alternatives
- Show practical examples of each alternative approach
- Explain when to use which alternative

### modules/sagas/pages/associations.adoc
**REWRITE OR REMOVE:**
**Changes to apply:**
- Either remove this page or rewrite to explain association patterns in alternatives
- If kept: Show how to implement association logic in custom event handlers
- Document querying state by association keys in custom storage

### modules/sagas/pages/implementation.adoc
**REWRITE:**
**Changes to apply:**
- Rewrite to show implementation patterns for alternatives
- **Stateful event handler pattern:**
  - How to maintain state across events
  - State storage strategies
  - Loading and updating state
- **Custom storage pattern:**
  - Choosing appropriate storage
  - State persistence
  - Querying state
- **Database check pattern:**
  - Scheduling regular checks (e.g., with scheduled tasks)
  - Checking state conditions
  - Triggering actions based on state

### modules/sagas/pages/infrastructure.adoc
**REWRITE OR REMOVE:**
**Changes to apply:**
- Rewrite to show infrastructure for alternative patterns
- Document storage configuration for custom solutions
- Show integration with Spring scheduling or similar for database checks
- Or remove if not applicable to alternatives

---

## Deadlines Module

**CRITICAL:** Deadlines are NOT available in Axon 5.0

### modules/deadlines/pages/index.adoc
**COMPLETE REWRITE:**
**Changes to apply:**
- Add prominent notice that **Deadlines are not available in Axon 5.0**
- Explain that Deadlines will be introduced with a renewed API in later versions
- **Document alternatives for Axon 5.0:**
  1. **Spring @Scheduled tasks** - Use Spring's scheduling for periodic checks
  2. **Database-based scheduling** - Store deadline/timeout information, check periodically
  3. **External scheduling frameworks** - Quartz, JobRunr, or similar job scheduling systems
- Provide migration patterns from Axon 4 Deadlines to these alternatives
- Show practical examples of each alternative approach
- Explain when to use which alternative
- Note: Event Scheduling also not available in current form

### modules/deadlines/pages/deadline-managers.adoc
**REWRITE OR REMOVE:**
**Changes to apply:**
- Rewrite to show alternative scheduling patterns or remove entirely
- If kept: Show implementation patterns for alternatives
- Document scheduled task configuration
- Show database-based deadline checking patterns

### modules/deadlines/pages/event-schedulers.adoc
**REWRITE OR REMOVE:**
**Changes to apply:**
- Note that event scheduling is not available in current form
- Rewrite to show alternatives for scheduling future actions
- Document using scheduled tasks to trigger events
- Or remove if not applicable to alternatives

---

## Messaging Concepts Module

### modules/messaging-concepts/pages/index.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Updated to reflect async-native messaging with dedicated section
- Introduced MessageStream concept with factory methods and usage examples
- Documented reactive programming support (imperative and reactive styles)
- Updated terminology for ProcessingContext with overview section
- Fixed MessageType vs class name issue - now correctly states MessageType identifies message structure
- Updated aggregate → entity terminology throughout
- Removed reference to GenericEventMessage.asEventMessage() (removed in Axon 5)
- Added "Understanding messages" section explaining message type, payload, metadata, and identifier
- Reorganized content for better flow
- Cross-referenced anatomy-message.adoc for detailed explanations
- Updated AxonIQ Platform references to AxonIQ Console

### modules/messaging-concepts/pages/anatomy-message.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Documented MessageType and QualifiedName as primary message identity
  - Explained fundamental shift: Java class is no longer message identity
  - MessageType = QualifiedName + version
  - Decouples message identity from Java representation
  - Enables payload conversion at handling time
- Updated Metadata to String-only values (Map<String, String>)
- Removed factory method references (GenericMessage.asMessage no longer exists)
- Documented message method renames (identifier(), payload(), metadata(), type(), etc.)
- Updated serialization to conversion flow (payloadAs, withConvertedPayload)
- Documented @Command, @Event, @Query annotations for defining message types
  - Explained namespace, name, and version parameters
  - Showed default behavior (uses FQCN if not annotated)
- Explained how payload conversion reduces need for upcasters
- Added comprehensive code examples throughout
- Reorganized content for better flow and understanding

### modules/messaging-concepts/pages/exception-handling.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Added major section on "Results vs exceptions" philosophy:
  - Explained that exceptions lose value in distributed systems
  - Emphasized using result objects for expected failures
  - Reserved exceptions for truly exceptional circumstances
  - Provided clear guidance on when to use each approach
  - Added practical code examples for both patterns
- Updated exception handling in distributed scenarios
- Documented HandlerExecutionException details map for structured error information
- Added TIP encouraging consideration of results vs exceptions
- Kept existing content about HandlerExecutionException and @ExceptionHandler
- Simplified interceptor-based error handling section
- Added reference to unit-of-work.adoc for ProcessingContext lifecycle details
- No RollbackConfiguration references found (already removed)
- Focused content on exception handling philosophy and practices rather than lifecycle callbacks

### modules/messaging-concepts/pages/message-correlation.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Updated correlation terminology to align with industry standards:
  - Old `traceId` → New `correlationId` (root/original message)
  - Old `correlationId` → New `causationId` (immediate parent message)
  - Added IMPORTANT callout explaining the terminology change
- Added "Understanding correlation" section with visual example showing message chain
- Documented updated MessageOriginProvider with correct correlationId and causationId
- Updated metadata type from Map<String, Object> to Map<String, String>
- Updated references from UnitOfWork to ProcessingContext
- Added comprehensive code examples showing correlation flow through message chains
- Added "Correlation in distributed scenarios" section:
  - Explained use cases (tracing, logging, debugging, monitoring)
  - Connected to observability tools (Zipkin, Jaeger, OpenTelemetry)
- Updated all code examples to use correct return types and method signatures
- Clarified that correlation data providers should not return null

### modules/messaging-concepts/pages/message-intercepting.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Complete rewrite with correct Axon 5 interceptor API:
  - MessageDispatchInterceptor: interceptOnDispatch(message, context, chain) returns MessageStream
  - MessageHandlerInterceptor: interceptOnHandle(message, context, chain) returns MessageStream
- Updated chain types: MessageDispatchInterceptorChain, MessageHandlerInterceptorChain
- Documented ProcessingContext parameter throughout (nullable for dispatch interceptors)
- Showed MessageStream return types in all examples
- Updated all code examples to use new API signatures
- Removed UnitOfWork references, replaced with ProcessingContext
- Documented using ProcessingContext for lifecycle callbacks (whenComplete, onError)
- Updated configuration examples to use MessagingConfigurer
- Kept @ExceptionHandler content (relatively unchanged)
- Showed command, event, and query interceptor examples for both dispatch and handler types
- Updated structural validation section
- Maintained annotated @MessageHandlerInterceptor content with correct patterns

### modules/messaging-concepts/pages/supported-parameters-annotated-handlers.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Replaced all UnitOfWork references with ProcessingContext
- Added ProcessingContext as injectable parameter in all handler types
- Added EventAppender parameter (available in command and event handlers)
- Added CommandDispatcher parameter (available in all handler types)
- Added QueryUpdateEmitter parameter (available in query handlers)
- Updated InterceptorChain to MessageHandlerInterceptorChain
- Added @AggregateType parameter (available in all handler types)
- Added @ReplayContext parameter (available in event handlers during replay)
- Removed DeadLetter parameter (not verified in Axon 5 implementation)
- Removed ScopeDescriptor reference (moved to legacy/stash, deadlines not available in 5.0)
- Fixed typo: Changed "CommandMessage" to "EventMessage" in event handler description
- Updated all message method name descriptions to current Axon 5 APIs
- Clarified when parameters are available (for example, @SequenceNumber and @SourceId only for aggregate-sourced events)
- Verified all parameter types against actual ParameterResolverFactory implementations in codebase
- Added comprehensive code examples section demonstrating:
  - ProcessingContext usage with cleanup actions
  - EventAppender for publishing follow-up events
  - Event handler with metadata and aggregate information
  - QueryUpdateEmitter for subscription queries
  - CommandDispatcher for command orchestration
  - ReplayContext handling during event replay
- Updated language throughout to be clearer and more concise

### modules/messaging-concepts/pages/timeouts.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Updated introduction to emphasize async-native design and ProcessingContext
- Replaced all UnitOfWork references with ProcessingContext
- Removed @DeadlineHandler references (deadlines not available in Axon 5.0)
- Removed deadline manager configuration properties
- Updated section title from "Unit of work timeouts" to "Processing context timeouts"
- Added comprehensive "Async processing and timeouts" section covering:
  - CompletableFuture timeout patterns with `.orTimeout()` and `.exceptionally()`
  - Framework-managed timeouts with Spring WebMVC (`@Async`)
  - Spring WebFlux reactive timeout examples with `Mono.timeout()`
  - Multiple timeout strategies:
    - Fail fast for user-facing operations
    - Long-running background operations
    - Retry with exponential backoff
  - Best practices for choosing appropriate timeouts
- Added practical code examples demonstrating:
  - Application-level timeout handling with CompletableFuture
  - Spring Boot automatic async timeout management
  - Reactive timeout handling with WebFlux
  - Synchronous blocking with timeout
  - Background processing timeout strategies
- Updated all code examples to use CommandDispatcher instead of CommandGateway
- Emphasized separation between handler timeouts and transaction/processing timeouts
- Provided clear guidance on when to use different timeout approaches

### modules/messaging-concepts/pages/unit-of-work.adoc → processing-context.adoc
**Status:** ✅ COMPLETED (MAJOR REWRITE)
**Changes applied:**
- **Renamed file** from `unit-of-work.adoc` to `processing-context.adoc` with page alias for backward compatibility
- **Updated all xrefs** across the entire reference guide to point to `processing-context.adoc`
- **Complete rewrite** focusing on ProcessingContext as the core abstraction for managing message processing lifecycle
- **Documented ProcessingLifecycle phases** with detailed explanation:
  - Pre-Invocation (-10000)
  - Invocation (0)
  - Post-Invocation (10000)
  - Prepare-Commit (20000)
  - Commit (30000)
  - After-Commit (40000)
- **Documented phase execution rules**: sequential phase execution, parallel actions within phases, failure handling
- **Documented lifecycle registration methods**: Both async (`on*`) and sync (`runOn*`) variants for all phases
- **Documented error and completion handlers**: `onError()`, `whenComplete()`, `doFinally()`
- **Replaced all UnitOfWork references** with ProcessingContext throughout
- **Removed CurrentUnitOfWork references** - documented that thread-local access no longer exists
- **Documented type-safe resource management** using `ResourceKey<T>`:
  - `putResource()`, `getResource()`, `containsResource()`
  - `computeResourceIfAbsent()`, `updateResource()`, `removeResource()`
  - Complete resource lifecycle patterns with code examples
- **Documented accessing framework components** via `component(Class<T>)` method
- **Documented accessing current message** via `Message.fromContext(context)`
- **Removed nesting functionality** - documented that contexts are independent, no `root()` equivalent
- **Documented context propagation** when dispatching messages:
  - Using EventAppender (automatic context propagation)
  - Using CommandDispatcher (explicit context passing)
  - Creating branched contexts with `withResource()`
- **Documented transaction management** integration with ProcessingContext
- **Documented rollback configuration** options
- **Added comprehensive code examples** demonstrating:
  - Injecting ProcessingContext in handlers
  - Registering lifecycle callbacks
  - Resource management patterns
  - Transaction interceptor implementation
  - Error handling patterns
  - Context propagation patterns
- **Added advanced usage section** covering:
  - Custom phases
  - Checking lifecycle state
  - Testing with StubProcessingContext
- **Added complete migration guide** from Axon 4 UnitOfWork with side-by-side comparison table
- **Documented key architectural changes**:
  - No thread-local access
  - No nesting support
  - Async-native with CompletableFuture
  - Type-safe resource keys
  - Message as resource (context doesn't revolve around single message)
- **Added best practices section** with 7 key recommendations
- **Updated Spring Boot and Axon Configuration examples** for transaction management
- All examples use correct Axon 5 APIs and patterns

---

## Monitoring Module

### modules/monitoring/pages/index.adoc
**Changes to apply:**
- Update overview for monitoring async-native architecture
- Document metrics for new components (PooledStreamingEventProcessor)
- Update module location (moved to extensions)

### modules/monitoring/pages/health.adoc
**Changes to apply:**
- Update health check examples
- Document health indicators for new processor types
- Update configuration approach

### modules/monitoring/pages/message-tracking.adoc
**Changes to apply:**
- Update correlation tracking (traceId, correlationId, causationId changes)
- Document ProcessingContext in tracking
- Update examples

### modules/monitoring/pages/metrics.adoc
**Changes to apply:**
- Update metrics for new event processor types
- Document metrics module location (extensions)
- Update Micrometer and Dropwizard integration
- Update configuration examples

### modules/monitoring/pages/processors.adoc
**Changes to apply:**
- Remove TrackingEventProcessor monitoring
- Document PooledStreamingEventProcessor monitoring
- Update processor status and control APIs
- Update AxonIQ Console integration examples

### modules/monitoring/pages/tracing.adoc
**Changes to apply:**
- Update tracing module location (extensions/tracing/opentelemetry)
- Document OpenTelemetry integration
- Update span creation for new APIs
- Document tracing with ProcessingContext
- Update distributed tracing examples

---

## Testing Module

**Status:** ✅ COMPLETED

### modules/testing/pages/index.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Complete rewrite introducing AxonTestFixture as the main testing tool
- Documented configurer-based testing approach (reuse production configuration)
- Explained benefits: no duplicate configuration, integration testing support
- Introduced Given-When-Then testing pattern
- Provided quick example with complete test class
- Added "Where to get the Configurer" section explaining production config reuse and creating test configurers
- Updated module contents to list all new pages

### modules/testing/pages/testing-with-axon-test-fixture.adoc
**Status:** ✅ COMPLETED (NEW FILE)
**Note:** Replaces commands-events.adoc
**Changes applied:**
- Comprehensive guide to using AxonTestFixture
- Creating fixtures from ApplicationConfigurer with customization options
- Complete documentation of all three phases:
  - Given phase: events, commands, noPriorActivity(), execute()
  - When phase: commands, events, nothing()
  - Then phase: success/exception, events, commands, results, await(), expect()
- Chaining multiple tests with and()
- Testing different scenarios: command handlers, event handlers, entities, queries
- Best practices and common patterns
- Examples with mocks, time-based logic, and external state

### modules/testing/pages/field-filters.adoc
**Status:** ✅ COMPLETED (NEW FILE)
**Changes applied:**
- Explained why field filtering is needed for non-deterministic fields
- Documented registerIgnoredField() and registerFieldFilter()
- Documented all built-in filters: AllFieldsFilter, NonStaticFieldsFilter, NonTransientFieldsFilter, IgnoreField, MatchAllFieldFilter
- Explained how field filters work recursively
- Provided common patterns: ignoring timestamps, UUIDs, custom annotations
- Comprehensive troubleshooting section
- Best practices for selective filtering

### modules/testing/pages/advanced-testing.adoc
**Status:** ✅ COMPLETED (NEW FILE)
**Changes applied:**
- Custom matchers: using built-in matchers and creating custom ones
- Integration testing with real infrastructure (event stores, processors)
- Testing with Spring Boot: reusing configuration, accessing/mocking beans
- Testing queries and subscription queries
- Testing message correlation and metadata propagation
- Testing with snapshots
- Testing time-based triggers (scheduled tasks, database-based scheduling)
- Performance testing considerations
- Best practices for advanced scenarios

### modules/testing/pages/upgrading-from-axon-4.adoc
**Status:** ✅ COMPLETED (NEW FILE)
**Changes applied:**
- Complete migration guide for Axon 4 users
- "What's new in Axon 5 testing" section explaining all improvements
- "Why the change?" section explaining Axon 4 problems and Axon 5 benefits
- Key differences: single fixture, configuration approach, method names
- Migration examples for aggregate tests and saga tests (converting to event handlers)
- Complete method name mapping table (Axon 4 → Axon 5)
- Common migration pitfalls with solutions
- Benefits of migration
- Step-by-step migration guide (10 steps)

### modules/testing/partials/nav.adoc
**Status:** ✅ COMPLETED
**Changes applied:**
- Updated navigation structure with new files:
  - testing-with-axon-test-fixture.adoc
  - field-filters.adoc
  - advanced-testing.adoc
  - upgrading-from-axon-4.adoc
- Removed old files: commands-events.adoc, sagas-1.adoc

### Old files removed:
- commands-events.adoc (replaced by testing-with-axon-test-fixture.adoc)
- sagas-1.adoc (sagas not available in Axon 5, alternatives covered in upgrading-from-axon-4.adoc)

---

## Tuning Module

### modules/tuning/pages/index.adoc
**Changes to apply:**
- Update performance tuning overview for async-native architecture
- Document PooledStreamingEventProcessor tuning
- Update terminology from aggregates to entities

### modules/tuning/pages/command-processing.adoc
**Changes to apply:**
- Remove DisruptorCommandBus tuning (removed from framework)
- Update entity loading and caching strategies
- Document Repository tuning
- Update async command processing patterns
- Update terminology from aggregate to entity

### modules/tuning/pages/event-processing.adoc
**Changes to apply:**
- Remove TrackingEventProcessor tuning entirely
- Document PooledStreamingEventProcessor tuning (thread pools, batch sizes)
- Document SequencingPolicy impact on performance
- Update token store considerations
- Document DCB impact on event sourcing performance
- Update streaming optimizations

### modules/tuning/pages/event-snapshots.adoc
**Changes to apply:**
- Update snapshotting for entities (not aggregates)
- Document snapshot triggering and creation changes
- Update converter/serialization for snapshots
- Document snapshot validation in test fixtures
- Update snapshot format considerations

### modules/tuning/pages/rdbms-tuning.adoc
**Changes to apply:**
- Update table schemas (aggregate_event_entry, etc.) for JPA
- Document global index sequence tuning
- Update JPA configuration (remains in core framework)
- Remove JDBC configuration references (moved to external extension)
- Document aggregate-based vs DCB-based storage performance
- Update connection pool recommendations

