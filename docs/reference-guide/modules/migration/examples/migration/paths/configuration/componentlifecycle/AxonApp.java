package migration.paths.configuration.componentlifecycle;

// tag::component-lifecycle[]
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

class AxonApp {

    public static void main(String[] args) {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();

        configurer.componentRegistry(cr -> cr.registerComponent(
                ComponentDefinition.ofType(MyComponent.class)
                                   .withBuilder(config -> new MyComponent())
                                   .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, component -> {})
                                   .onShutdown(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, component -> {})
        ));
    }
}
// end::component-lifecycle[]

class MyComponent {
}
