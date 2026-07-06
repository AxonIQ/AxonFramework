package migration.paths.dlq.processingsequences;

// tag::retry-dead-lettered-sequence[]
import org.axonframework.common.configuration.Configuration;
import io.axoniq.framework.messaging.deadletter.SequencedDeadLetterProcessor;
import java.util.concurrent.TimeUnit;

public class DeadLetterProcessor {

    private Configuration configuration;

    public void retryAnySequence(String processorName, String componentName) {
        configuration.getModuleConfiguration(processorName)
                .flatMap(m -> m.getOptionalComponent(SequencedDeadLetterProcessor.class, "EventHandlingComponent[" + processorName + "][" + componentName + "]"))
                .ifPresent(dlp ->
                        dlp.processAny()
                           .orTimeout(30, TimeUnit.SECONDS)
                           .join());
    }
}
// end::retry-dead-lettered-sequence[]
