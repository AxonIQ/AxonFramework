# Axon Framework 5 Reference Guide Migration Instructions

## Project Overview

This is a gradual migration project to update the Axon Framework reference guide from Axon 4 to Axon 5. The reference guide is located in `docs/reference-guide/` and uses Antora for documentation.

Don't emphasize changes in the documentation. Instead, describe the new status as if it is the status quo. If there are very significant changes compared to Axon 4, you can describe them briefly in a NOTE section.

Axon 5 includes fundamental architectural changes:
- Messaging-centric approach (Commands, Events, and Queries are equally important)
- Shift away from modeling-centric focus of Axon 4
- Aggregates → Entities (entities are now implementation patterns, not core concepts)
- UnitOfWork → ProcessingContext (replaces ThreadLocal-based CurrentUnitOfWork)
- EventBus → EventSink (EventSink is the publishing side; introduce this carefully as EventBus is well-known)
- Serializer → Converter
- Async-native APIs throughout
- Dynamic Consistency Boundary (DCB) for event stores
- TrackingEventProcessor removed, replaced by PooledStreamingEventProcessor
- **Sagas not available in Axon 5.0** - will be reintroduced in different format later
- **Deadlines not available in Axon 5.0** - will be introduced with renewed API later
- Test fixtures completely rewritten

## Key Documents

1. **`axon-5/api-changes/`** - Comprehensive API changes documentation, split into focused per-topic files. Start at [`axon-5/api-changes/index.md`](../axon-5/api-changes/index.md) for the table of contents and load individual files (01–12) for the area you are migrating.
   - **NOTE:** These documents mention legacy components as usable, but the documentation policy is that legacy = not available
2. **`axon-5/`** - Other design documents describing the changes
3. **`docs/changes-to-process.md`** - Master tracking document listing all reference guide files and the changes needed for each

## Workflow

1. Select a section from `changes-to-process.md` to work on
2. Read the corresponding reference guide file in `docs/reference-guide/modules/`
3. Consult `axon-5/api-changes/` (start with `index.md`) and other design docs for API details
4. **IMPORTANT:** Always verify against actual code in the framework before making changes
5. Update the reference guide file with the necessary changes
6. Validate al code examples in the reference guide file using the framework code
7. **CRITICAL:** Mark progress in `changes-to-process.md` as sections are completed
   - Change status from "**Changes to apply:**" to "**Status:** ✅ COMPLETED"
   - Add "**Changes applied:**" section listing all changes made
   - This tracking is MANDATORY - never skip this step

## Important Guidelines

### Terminology
- Use "entity" instead of "aggregate" when discussing modeling patterns
- Aggregates are just one type of entity implementation
- **Commands, Events, and Queries are equally important** - emphasize messaging over modeling
- Entities are implementation patterns/tools, not core framework concepts
- The shift is from modeling-centric (Axon 4) to messaging-centric (Axon 5)
- **EventBus → EventSink**: Be careful here
  - EventBus is a well-known concept to users
  - EventSink is more technical and specifically represents the publishing/sending side
  - Introduce EventSink clearly and explain its relationship to the EventBus concept. Make clear that EventSink could also be used when sending Events to a 3rd-party eventing system.
  - Don't just blindly rename - help users understand the shift

### ProcessingContext Usage
**Critical distinction between dispatching and handling:**

**Dispatching side (EventSink, CommandBus, QueryBus):**
- ProcessingContext is **OPTIONAL**
- Provide it when available (e.g., dispatching from within a handler) to propagate correlation data
- When dispatching from outside a handler (e.g., HTTP endpoint), pass `null` or use method variants without ProcessingContext parameter
- Purpose: Allows correlation data to flow from one message to another

**Handling side (all message handlers):**
- ProcessingContext is **MANDATORY**
- Axon always creates one when processing a message
- Must be injected and passed to all components involved in handling the message
- Enables coordination between components during message processing
- Components can inject ProcessingContext as a handler parameter

### Message Types and Payload Conversion
**Critical conceptual shift from Axon 4:**

**Axon 4 approach:**
- Java class representation was the identity of the message
- Handler declared a specific Java class, message had to match exactly
- Upcasters were needed to convert old message formats to new Java classes

**Axon 5 approach:**
- Messages have a **MessageType** (QualifiedName + version) decoupled from Java class
- Handlers declare the message type they support (directly or indirectly via Java class parameter)
- **Payload is converted to handler's expected type at handling time**
- Different handlers can receive the same message in different Java representations
- Makes many upcasters unnecessary - conversion happens during handling, not deserialization

**Important distinction - MessageType vs identifier:**
- **MessageType**: Identifies what TYPE/STRUCTURE of message it is (e.g., "OrderPlaced v1.0")
  - Describes what structure/content to expect
  - Many messages can share the same MessageType
  - Acts as a type/schema identifier
- **identifier**: Uniquely identifies a SPECIFIC message instance
  - Like a UUID for that particular message occurrence
  - Stays the same even if message is represented in different Java classes
  - Acts as an instance identifier

**Documentation implications:**
- Emphasize that Java class is no longer the message identity
- Explain MessageType (QualifiedName + version) as the true message TYPE identifier
- Clarify that identifier is the instance identifier (not MessageType)
- Show how handlers declare types (via @Event/@Command/@Query annotations or parameter types)
- Demonstrate payload conversion at handling time
- Explain when upcasters are still needed vs when simple conversion suffices

**Handler annotation requirements:**
- When handler parameter is @Event/@Command/@Query annotated: Framework derives MessageType from annotation
- When handler parameter is NOT annotated (JsonNode, Map, String, etc.): **MUST specify messageType attribute**
  - Example: `@EventHandler(messageType = "com.example.OrderPlaced")`
  - This tells Axon which message type the handler should receive

### Exception Handling Philosophy
**Important shift in approach:**

In distributed systems, exceptions often lose value when crossing boundaries:
- Exception details may not be meaningful to receivers
- Different services may not share same exception classes
- Receivers need structured information to make decisions

**Guidance for documentation:**
- **Use result objects for expected failure scenarios:**
  - Validation failures, business rule violations, authorization failures
  - These are not exceptional - they're expected possibilities
  - Result objects can carry structured information about failures
- **Reserve exceptions for truly exceptional circumstances:**
  - Infrastructure failures (database unavailable, network timeout)
  - Programming errors (null pointer, illegal state)
  - Configuration errors
  - Situations where normal processing cannot continue

**Documentation approach:**
- Emphasize results vs exceptions distinction in exception handling sections
- Show result object patterns alongside exception handling
- Guide users toward appropriate error handling strategy for their scenario

### Module Structure
- JPA remains in core framework
- JDBC has moved to external extensions (remove references for now)
- Spring has moved to extensions
- Monitoring and tracing moved to extensions

### Removed Components
**DomainEventMessage:**
- `DomainEventMessage` has been removed in Axon 5
- No longer distinguishes between domain events and regular events
- All events are now `EventMessage`
- Remove references to DomainEventMessage in documentation
- Update examples to use EventMessage only

### Legacy Package = Not Available
**Critical policy for documentation:**
- **Anything moved to "legacy package" is effectively NOT available in Axon 5.0**
- Legacy components will either:
  1. Be reintroduced later in a different format, OR
  2. Be permanently replaced by another mechanism
- Do NOT document legacy package as if it's usable in Axon 5.0
- Do NOT suggest users can use legacy components
- **Important:** the `axon-5/api-changes/` files mention legacy components as available, but for documentation purposes treat them as unavailable
- Focus documentation on alternatives and replacement mechanisms

### Components Not Available in Axon 5.0

#### Sagas - Not Available
**Critical information:**
- Sagas are NOT available in Axon 5.0 (moved to legacy)
- They will be reintroduced in a later version in a different format
- **Alternatives to document:**
  1. **Stateful event handlers** - Event handlers that maintain state across events
  2. **Event handlers with custom state storage** - Store and retrieve state from database/cache
  3. **Scheduled checks** - Regular scheduled checks on state instead of deadline/saga scheduling
- Documentation should guide users through migration from Axon 4 Sagas to these alternatives
- Provide practical examples and patterns for each alternative approach

#### Deadlines - Not Available
**Critical information:**
- Deadlines are NOT available in Axon 5.0
- They will be introduced with a renewed API in later versions
- **Alternatives to document:**
  1. **Scheduled tasks** - Use Spring's `@Scheduled` or similar scheduling mechanisms
  2. **Database-based scheduling** - Store deadline information in database, check periodically
  3. **External scheduling systems** - Quartz, JobRunr, or similar job scheduling frameworks
- Documentation should guide users through alternatives
- Provide practical examples of time-based triggers without deadline framework

### Files to Rename
All aggregate-related files should be renamed to entity equivalents:
- `aggregate.adoc` → `event-sourced-entity.adoc`
- `multi-entity-aggregates.adoc` → `entity-hierarchies.adoc` or `child-entities.adoc`
- etc. (see `changes-to-process.md` for full list)

### Files to Rename
**IMPORTANT: This is an incremental migration. Files are renamed gradually, not all at once.**

When working on a file:
1. **ALWAYS verify xref targets exist** before using them
2. Use the OLD filename in xrefs until the target file is actually renamed
3. When you rename a file, update ALL xrefs pointing to it across the entire reference guide

**Workflow for handling xrefs:**
1. Before writing an xref, check if the target file exists with the new name
2. If the target file doesn't exist yet, use the old filename in the xref
3. Add a comment noting that the xref should be updated when the target is renamed: `// TODO: Update to new-name.adoc when file is renamed`

**Example:**
```adoc
// WRONG - references file that doesn't exist yet:
xref:axon-framework-commands:modeling/event-sourced-entity.adoc[Entity]

// CORRECT - uses current filename:
xref:axon-framework-commands:modeling/aggregate.adoc[Entity]
// TODO: Update to event-sourced-entity.adoc when aggregate.adoc is renamed
```

**When renaming a file:**
1. Rename the physical file
2. Use `Grep` to find ALL xrefs to the old filename across the entire `docs/reference-guide/` directory
3. Update every xref to use the new filename
4. Remove any TODO comments about updating that xref

**To find all xrefs to a file:**
```
grep -r "xref:.*old-filename.adoc" docs/reference-guide/
```

### Files to Remove
- `upgrading-to-4-7.adoc` - Will be replaced with Axon 4 to 5 migration guide separately
- Release notes pages - Will be updated in separate task

### Code Examples
- **Always provide code samples where appropriate** - this was a weak spot in previous documentation
- Show both **Spring configuration** and **plain Java configuration** examples
- Spring examples should use:
  - Spring Boot auto-configuration where applicable
  - `@EventSourced` annotation for entities
  - Bean-based registration for components
- Plain configuration examples should use:
  - `MessagingConfigurer`, `ModellingConfigurer`, or `EventSourcingConfigurer` as appropriate
  - Explicit component registration
  - ComponentBuilder and ComponentDefinition patterns
- Use realistic, complete examples that users can actually use
- Ensure code examples reflect Axon 5 APIs (ProcessingContext, EventAppender, etc.)
- **CRITICAL: Verify all APIs before writing code examples**
  - Check actual method signatures in the framework
  - Verify return types (e.g., `CommandResult` not `CompletableFuture`)
  - Verify parameter types
  - Don't assume APIs work like you expect

### Documentation Approach
- **Be user-centric**: Focus on "how do I accomplish X" rather than "how does X work internally"
- Explain what users need to do to get things done
- Avoid going too deep into implementation details unless necessary for understanding
- Keep the focus on practical usage and configuration
- Internal workings should only be explained when they affect user decisions
- Examples:
  - ✅ "To configure an event processor, use the EventProcessorModule..."
  - ❌ "The event processor internally uses a token store which maintains..."
  - ✅ "When choosing between X and Y, consider..." (when implementation matters for choice)

### Verification
**CRITICAL - Always verify before writing code:**
- Do NOT make assumptions about API details - **ALWAYS check the actual framework code**
- API methods may have changed in ways not fully documented
- Example: `commandGateway.send()` returns `CommandResult` (which provides access to CompletableFuture), not CompletableFuture directly
- **Before writing ANY code example:**
  1. Search for the actual class/interface in the framework code
  2. Read the method signatures
  3. Verify return types and parameters
  4. Check for any related classes (like `CommandResult`)
- API changes documentation may not cover everything
- When in doubt, ask for clarification or verification
- **Better to ask than to document incorrect APIs**

## Writing Style

### Style Guide Configuration
The documentation uses Vale for style checking. **Errors will prevent the documentation site from building.**

### Critical Rules (ERROR level - will break build)

#### 1. Acronym Capitalization
Always capitalize acronyms correctly:
- ✅ API, APIs, HTTP, HTTPS, JPA, JSON, JVM, gRPC, AMQP, CDI, BOM, DSL, OSGi, SSH, SSL, TCP, URI, URL, YAML, YML
- ❌ api, http, jpa, json, jvm, grpc, amqp
- Note: "api" is allowed as a suffix in module names (e.g., "core-api")

#### 2. Heading Capitalization
- **H1 headings**: Use title-style capitalization (Chicago style)
  - Example: "Understanding the Event Store"
- **H2-H6 headings**: Use sentence-style capitalization (only first word and proper nouns capitalized)
  - ✅ "Configuring the event store"
  - ❌ "Configuring The Event Store"
  - Exceptions: Proper product names (see list below) remain capitalized

#### 3. Proper Names
Always capitalize these names correctly:
- **Products/Frameworks**: Antora, Datadog, Dropwizard, GitHub, Gradle, JGroups, JUnit, Kafka, Kotlin, Logback, Mockito, OAuth, PostgreSQL, Testcontainers, XStream
- **Axon Products**: Axon Framework, Axon Server, AxonIQ Console, AxonIQ Cloud
- **Operating Systems**: macOS, Windows, Linux
- ❌ github, gradle, postgres, axon framework

#### 4. Axon-Specific Capitalization Exceptions
These terms are exceptions to sentence-case rules in H2-H6 headings:
- Axon Framework, Axon Server, Axon Configuration, Axon Messaging, Axon Test, AxonIQ Console
- Command-Query Responsibility Separation (CQRS)
- Domain-Driven Design (DDD)
- Event Sourcing
- Spring, Spring Boot, Spring Boot Starter
- OpenTelemetry, OpenTracing
- And other product names (see full list in Headings.yml)

### Warnings (won't break build but should avoid)
- Remove annotation comments like TODO, FIXME, XXX, NOTE before committing
- Watch for common misspellings (e.g., "poplar" instead of "popular")
- Avoid overly long sentences (Vale may flag sentences that are too complex - break them into shorter, clearer sentences)

### Em-dash prohibition
**Never use em-dashes (`—`) in documentation.** Em-dashes read as machine-generated and make the text feel less human. Use alternative punctuation instead:
- Replace `—` with a comma, colon, semicolon, or parentheses depending on the context
- ✅ "The processor starts immediately, consuming from the head of the stream."
- ✅ "The processor starts immediately (consuming from the head of the stream)."
- ❌ "The processor starts immediately — consuming from the head of the stream."

### ASCII-only text
**All `.adoc` files must contain only ASCII characters.** Never use:
- Curly/smart quotes: `"` `"` `'` `'` — use straight `"` and `'` instead
- Em-dash: `—` — use a comma, colon, or parentheses instead (see above)
- Ellipsis: `…` — use three dots `...` instead
- Any other non-ASCII Unicode character

**LF line endings only** — never use CR (`\r`) or CRLF. Configure your editor to write LF.

---

## Critical Reminders

### After Completing Any Documentation Update

**YOU MUST:**
1. ✅ Update `docs/changes-to-process.md` to mark the section as completed
2. ✅ Verify all xrefs point to existing files (use old filenames until files are renamed)
3. ✅ Check style guide compliance (heading capitalization, acronyms, product names)

**When renaming a file:**
1. ✅ Use `Grep` to find ALL xrefs to the old filename across entire reference guide
2. ✅ Update every xref to use the new filename
3. ✅ Remove any TODO comments about updating that xref

---

**Note for AI Agent:** When starting a new session, read this file and `docs/changes-to-process.md` to understand the current state of the migration. Ask the user which section they'd like to work on next.
