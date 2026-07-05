package commands.entities.entitypolymorphism.autodetected;

record CourseCreated(String courseId,
                     String title,
                     int capacity,
                     CourseType courseType,
                     String platformUrl,
                     String location) {
}
