package migration.paths.projectorseventprocessors.namespace;

// tag::namespace-projector[]
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

@Namespace("my-processor")
public class MyProjector {

    @EventHandler
    public void on(MyEvent event) {
        // ...
    }
}
// end::namespace-projector[]

record MyEvent() {

}
