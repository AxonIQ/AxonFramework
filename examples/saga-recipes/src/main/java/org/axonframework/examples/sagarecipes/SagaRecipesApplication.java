package org.axonframework.examples.sagarecipes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EntityScan(basePackages = {
        "org.axonframework.examples.sagarecipes",
        "org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa",
        "org.axonframework.eventsourcing.eventstore.jpa"
})
public class SagaRecipesApplication {

    public static void main(String[] args) {
        SpringApplication.run(SagaRecipesApplication.class, args);
    }
}
