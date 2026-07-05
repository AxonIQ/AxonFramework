package commands.entities.entitypolymorphism.declarative;

record CourseCreated(String courseId,
                     String title,
                     int capacity,
                     CourseType courseType,
                     String platformUrl,
                     String location) {
}
