package events.infrastructure.jpa;

// tag::custom-entity-manager-provider[]
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.axonframework.common.jpa.EntityManagerProvider;

public class MyEntityManagerProvider implements EntityManagerProvider {

    private EntityManager entityManager;

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @PersistenceContext(unitName = "myPersistenceUnit")
    public void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }
}
// end::custom-entity-manager-provider[]
