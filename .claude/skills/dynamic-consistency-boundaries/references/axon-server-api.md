# Axon Server DCB event store API

Axon Server implements DCB directly in the event store. This is the public gRPC contract:
https://github.com/AxonIQ/axon-server-api/blob/master/src/main/proto/dcb.proto

The framework's `EventStorageEngine` for Axon Server maps onto these RPCs. Use this reference to
answer "what can the DCB event store actually do?" -- the proto is the source of truth for the
capability surface.

## Services and RPCs (verbatim)

### DcbEventStore

```
rpc Append        (stream AppendEventsRequest) returns (AppendEventsResponse)
rpc Source        (SourceEventsRequest)        returns (stream SourceEventsResponse)
rpc Stream        (StreamEventsRequest)        returns (stream StreamEventsResponse)
rpc GetHead       (GetHeadRequest)             returns (GetHeadResponse)
rpc GetTail       (GetTailRequest)             returns (GetTailResponse)
rpc GetSequenceAt (GetSequenceAtRequest)       returns (GetSequenceAtResponse)
rpc AddTags       (AddTagsRequest)             returns (AddTagsResponse)
rpc RemoveTags    (RemoveTagsRequest)          returns (RemoveTagsResponse)
rpc GetTags       (GetTagsRequest)             returns (GetTagsResponse)
```

### DcbSnapshotStore

```
rpc Add     (AddSnapshotRequest)      returns (AddSnapshotResponse)
rpc Delete  (DeleteSnapshotsRequest)  returns (DeleteSnapshotsResponse)
rpc List    (ListSnapshotsRequest)    returns (stream ListSnapshotsResponse)
rpc GetLast (GetLastSnapshotRequest)  returns (GetLastSnapshotResponse)
```

### DcbEventScheduler

```
rpc ScheduleEvent       (ScheduleEventRequest)        returns (ScheduleToken)
rpc RescheduleEvent     (RescheduleEventRequest)      returns (ScheduleToken)
rpc CancelScheduledEvent(CancelScheduledEventRequest) returns (InstructionAck)
```

## Key messages (verbatim fields)

```
message Event {
  string identifier;
  int64  timestamp;
  string name;                       // event type/name used for matching
  string version;
  bytes  payload;
  map<string,string> metadata;
}

message Tag        { bytes key; bytes value; }
message TaggedEvent{ Event event; repeated Tag tag; }
message SequencedEvent { int64 sequence; Event event; }

message Snapshot {
  string name; string version; bytes payload; int64 timestamp; map<string,string> metadata;
}

// --- DCB consistency ---
message ConsistencyCondition {
  int64 consistency_marker;          // the head captured at read time
  repeated Criterion criterion;      // OR across criterions
}
message Criterion { TagsAndNamesCriterion tags_and_names; }
message TagsAndNamesCriterion {
  repeated string name;              // event type set: event.name must be one of these (OR)
  repeated Tag    tag;               // required tags: event must carry all of these (AND)
}

// --- append ---
message AppendEventsRequest  { ConsistencyCondition condition; repeated TaggedEvent event; }
message AppendEventsResponse { int64 sequence_of_the_first_event; int32 transaction_size; int64 consistency_marker; }

// --- read (finite) ---
message SourceEventsRequest  { int64 from_sequence; repeated Criterion criterion; }
message SourceEventsResponse { oneof result { SequencedEvent event; int64 consistency_marker; } }

// --- read (infinite / tracking) ---
message StreamEventsRequest  { int64 from_sequence; repeated Criterion criterion; }
message StreamEventsResponse { SequencedEvent event; }

// --- positions ---
message GetHeadRequest {}              message GetHeadResponse { int64 sequence; }
message GetTailRequest {}              message GetTailResponse { int64 sequence; }
message GetSequenceAtRequest { int64 timestamp; }  message GetSequenceAtResponse { int64 sequence; }

// --- mutable tags ---
message AddTagsRequest    { int64 sequence; repeated Tag tag; }
message RemoveTagsRequest { int64 sequence; repeated Tag tag; }
message GetTagsRequest    { int64 sequence; }   message GetTagsResponse { repeated Tag tag; }
```

## Matching semantics (the rule)

An event matches a `Criterion` (`TagsAndNamesCriterion`) when:
- its `name` is in the criterion's `name` list (OR across names; empty list means any type), AND
- it carries every `Tag` in the criterion's `tag` list (AND across tags).

A request's `repeated Criterion` is an OR: the event matches if it matches any criterion. This is
exactly Axon Framework's `EventCriteria.havingTags(...).andBeingOneOfTypes(...)` composed with
`EventCriteria.either(...)`.

## What is possible

- **Append a transaction of tagged events under a consistency condition** (`Append`): all-or-
  nothing, validated against `consistency_marker` + `criterion`. The response returns the first
  assigned sequence, the transaction size, and the new consistency marker.
- **Source a finite set of events for a decision** (`Source`): filtered by `from_sequence` and
  criterions; the stream ends with a `consistency_marker` value to use when appending.
- **Stream events live for projections / tracking** (`Stream`): same filtering, infinite,
  client-cancellable.
- **Optimistic concurrency** purely via `consistency_marker` -- no row locks, no per-aggregate
  revision column.
- **Position queries**: `GetHead` (next sequence), `GetTail` (oldest retained), `GetSequenceAt`
  (sequence at a timestamp -- useful for time-based replays).
- **Mutable tags after the fact**: `AddTags` / `RemoveTags` / `GetTags` by sequence. Tags are not
  frozen at append time; a boundary can be reshaped by re-tagging existing events.
- **Snapshots** of decision-model state (`DcbSnapshotStore`) to shorten sourcing.
- **Event scheduling** (`DcbEventScheduler`): publish an event at a future time, reschedule, or
  cancel (deadline-style behavior).

## What is NOT possible (by the contract)

- **No content-based filtering.** Matching is only on event `name` and `tag`. You cannot query by
  payload fields or metadata values. Counting, thresholds, ranges, and predicates are computed
  client-side by folding the sourced events into the decision model.
- **No server-side aggregation.** There is no count/sum/exists RPC; the client sources and folds.
- **No tag operators beyond equality + presence.** Tags match by exact key/value; there are no
  ranges, prefixes, or negation. Model anything richer as distinct tag values or event types.
- **No cross-criterion AND at the request level.** The `repeated Criterion` is OR-only; an AND of
  conditions must be expressed as tags within a single criterion.
- **No per-stream API.** There are no stream identifiers -- only one global log selected by
  criteria. "Stream" here means a gRPC server-stream, not an event stream in the AF4 sense.

## Relationship to the framework types

| Proto | Axon Framework |
|---|---|
| `ConsistencyCondition.consistency_marker` | `ConsistencyMarker` (`GlobalIndexConsistencyMarker`) |
| `Criterion` / `TagsAndNamesCriterion` | `EventCriteria` (a criterion) |
| `repeated Criterion` | `EventCriteria.either(...)` |
| `SourceEventsRequest` | `SourcingCondition` |
| `AppendEventsRequest.condition` | `AppendCondition` |
| `TaggedEvent` | `TaggedEventMessage` |
| `Tag` | `Tag` |
| `SequencedEvent.sequence` | global index / `Position` |
