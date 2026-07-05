package events.eventprocessors.index.lifecycle;

// tag::processor-state-checker[]
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;

public class ProcessorStateChecker {

    private final AxonConfiguration configuration;

    public ProcessorStateChecker(AxonConfiguration configuration) {
        this.configuration = configuration;
    }

    public void printState(String processorName) {
        EventProcessor processor = configuration.getComponent(EventProcessor.class, processorName);
        System.out.println("Running: " + processor.isRunning());
        System.out.println("Error:   " + processor.isError());
    }
}
// end::processor-state-checker[]
