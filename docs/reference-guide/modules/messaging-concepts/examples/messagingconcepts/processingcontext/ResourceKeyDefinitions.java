package messagingconcepts.processingcontext;

import java.util.List;
// The framework import is part of the rendered snippet, so it sits inside its own tag. It is
// indented to the same depth as the key declarations below so a single indent=0 include normalizes
// both tag regions to the left margin, keeping the rendered snippet byte-identical.
// tag::resource-key-import[]
    import org.axonframework.messaging.core.Context.ResourceKey;
// end::resource-key-import[]

interface ResourceKeyDefinitions {

    // tag::resource-keys[]

    // Define resource keys
    ResourceKey<EntityManager> EM_KEY = ResourceKey.withLabel("EntityManager");
    ResourceKey<Connection> DB_CONN = ResourceKey.withLabel("DatabaseConnection");
    ResourceKey<List<String>> TAGS = ResourceKey.withLabel("Tags");
    // end::resource-keys[]
}
