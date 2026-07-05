package commands.infrastructure.registration;

import org.axonframework.modelling.repository.Repository;

class OrderCommandHandler {

    private final Repository repository;

    OrderCommandHandler(Repository repository) {
        this.repository = repository;
    }
}
