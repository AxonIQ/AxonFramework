# Event Processing Context Ownership

Do not infer processing context ownership from event processor type.

A subscribing processor may receive the publisher's `ProcessingContext` from a local event bus, or a source-owned
context from another event source such as a Persistent Stream. Base behavior that depends on context ownership on the
actual delivery path, not on whether the processor is subscribing or streaming.
