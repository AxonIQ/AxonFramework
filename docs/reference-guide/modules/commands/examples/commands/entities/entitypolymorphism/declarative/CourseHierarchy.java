package commands.entities.entitypolymorphism.declarative;

// The hierarchy is nested in an interface so the classes are implicitly static: the page can then
// display them as top-level classes (indent=0) while OnlineCourse and InPersonCourse remain
// instantiable from CourseEntityConfiguration without an enclosing instance.
interface CourseHierarchy {

    // tag::declarative-hierarchy[]
    // Abstract parent, no annotations needed
    public abstract class CourseEntity {

        protected String courseId;
        protected String title;
        protected int capacity;
        protected int enrolledCount;
    }

    // Concrete subtypes
    public class OnlineCourse extends CourseEntity {

        protected String platformUrl;

        public OnlineCourse(CourseCreated event) {
            this.courseId = event.courseId();
            this.title = event.title();
            this.capacity = event.capacity();
            this.platformUrl = event.platformUrl();
        }
    }

    public class InPersonCourse extends CourseEntity {

        protected String location;

        public InPersonCourse(CourseCreated event) {
            this.courseId = event.courseId();
            this.title = event.title();
            this.capacity = event.capacity();
            this.location = event.location();
        }
    }
    // end::declarative-hierarchy[]
}
