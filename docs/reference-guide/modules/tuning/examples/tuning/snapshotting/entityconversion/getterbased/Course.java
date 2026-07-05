package tuning.snapshotting.entityconversion.getterbased;

// tag::getter-based-course[]
public class Course {
    private final String courseId;
    private final String name;
    private final int capacity;

    public Course(String courseId, String name, int capacity) {
        this.courseId = courseId;
        this.name = name;
        this.capacity = capacity;
    }

    public String getCourseId() { return courseId; }
    public String getName() { return name; }
    public int getCapacity() { return capacity; }
}
// end::getter-based-course[]
