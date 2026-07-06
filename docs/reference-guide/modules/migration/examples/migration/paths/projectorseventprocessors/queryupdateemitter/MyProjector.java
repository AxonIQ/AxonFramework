package migration.paths.projectorseventprocessors.queryupdateemitter;

// tag::query-update-emitter-parameter[]
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;

public class MyProjector {

    @EventHandler
    public void on(MyEvent event, QueryUpdateEmitter updateEmitter) {
        updateEmitter.emit(MyQuery.class, q -> true, new MyView());
    }
}
// end::query-update-emitter-parameter[]

record MyEvent() {

}

record MyQuery() {

}

record MyView() {

}
