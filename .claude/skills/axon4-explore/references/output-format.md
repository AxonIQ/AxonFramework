# axon4-explore — output format

Each detected aggregate produces one YAML document at
`<project>/.axon4-explore/components/aggregate@<fqn>.yaml` with the schema below. The same
data lands as a flat row in
`<project>/target/rewrite/datatables/<timestamp>/org.axonframework.migration.find.AggregateFeaturesTable.csv`
(commands and events comma-joined).

## Schema

```yaml
component_type: aggregate              # always "aggregate" in v1; future: "saga", "projection", …
class_name: Bike                       # simple class name
package: io.axoniq.demo.bikerental.rental.command
fqn: io.axoniq.demo.bikerental.rental.command.Bike
language: java                         # "java" | "kotlin"
file_path: rental/src/main/java/io/axoniq/demo/bikerental/rental/command/Bike.java

discovery:                             # which signals identified this class as an aggregate
  - "@Aggregate"                       # any subset of: "@Aggregate", "configureAggregate", "withSubtypes"

config_style: spring                   # "spring" if @Aggregate present, else "non-spring"
persistence: event-sourcing            # "event-sourcing" if any @EventSourcingHandler present, else "state-stored"

features:
  multi_entity: false                  # any field carries @AggregateMember
  deadline: false                      # any method carries @DeadlineHandler
  create_new: false                    # any method invocation in this class is named createNew(...)
  polymorphic: false                   # Signal 1 (extends-chain) OR Signal 2 (withSubtypes) flagged this class
  polymorphic_role: null               # null | "parent" | "child"
  polymorphic_parent: null             # FQN of parent if role=child, else null
  has_snapshot_trigger: true           # @Aggregate(snapshotTriggerDefinition = …)
  has_cache: false                     # @Aggregate(cache = …)
  has_revision: false                  # class carries @Revision
  has_aggregate_version: false         # any field carries @AggregateVersion
  has_creation_policy: false           # any method carries @CreationPolicy
  jpa_entity: false                    # class carries @jakarta.persistence.Entity or @javax.persistence.Entity

commands:                              # FQN of the first parameter of every @CommandHandler method
  - io.axoniq.demo.bikerental.coreapi.rental.RegisterBikeCommand
  - io.axoniq.demo.bikerental.coreapi.rental.ReturnBikeCommand

events:                                # FQN of the first parameter of every @EventHandler / @EventSourcingHandler method
  - io.axoniq.demo.bikerental.coreapi.rental.BikeRegisteredEvent
  - io.axoniq.demo.bikerental.coreapi.rental.BikeReturnedEvent

imports:                               # subset of Axon-related imports detected on the class
  - org.axonframework.spring.stereotype.Aggregate
  - org.axonframework.eventsourcing.EventSourcingHandler
  - org.axonframework.commandhandling.CommandHandler

notes: []                              # transparency: ambiguous detections, polymorphism source signal, etc.
```

## Field-by-field notes

| Field | Source | Notes |
|---|---|---|
| `component_type` | constant | Always `aggregate` today. Future expansion to other component types uses the same `<type>@<fqn>.yaml` filename scheme. |
| `discovery` | scanner passes | Multi-valued: a class can be discovered by `@Aggregate` AND `configureAggregate` simultaneously. Kept as a list to preserve provenance. |
| `config_style` | derived | `spring` exclusively when the class carries `@Aggregate`; `non-spring` for classes only discovered via `configureAggregate(...)`. |
| `persistence` | derived | A single `@EventSourcingHandler` anywhere in the class flips this to `event-sourcing`. Default `state-stored`. |
| `features.polymorphic_role` | derived | When both signals fire, Signal 1 (extends-chain) wins for role assignment; the `notes` field records both signals. |
| `commands` | per-method scan | First parameter of every `@CommandHandler`-annotated method (including constructors). Both AF4 and AF5 `@CommandHandler` FQNs match. |
| `events` | per-method scan | Union of `@EventHandler` and `@EventSourcingHandler` first-parameter types. On AF4 aggregates `@EventSourcingHandler` is the dominant form. |
| `imports` | scanner | Captures the load-bearing Axon imports for downstream LLM context. Not a complete import list. |
| `notes` | merge | Free-form strings flagging detection ambiguity (e.g. "polymorphism detected via: extends-chain"). Empty list when everything is unambiguous. |

## When type resolution fails

If Axon 4 is not on the project's compile classpath, some annotations may not be type-resolved.
The recipe falls back to simple-name matching for `@Aggregate`, `@EventHandler`,
`@EventSourcingHandler`, `@CommandHandler`, `@AggregateMember`, `@DeadlineHandler`,
`@CreationPolicy`, `@AggregateVersion`, `@Revision`, and `@Entity`. This can produce false
positives if the project defines its own annotations with these simple names.

When type resolution fails for a method's first parameter, the FQN is omitted (the entry is
dropped from `commands` / `events` rather than appearing as `<unresolved>`). Look at the
project's compile output if `commands` or `events` are unexpectedly empty.
