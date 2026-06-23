package org.axonframework.examples.demo.university.entity.polymorphism;

import org.axonframework.modelling.annotation.TargetEntityId;

public record CreateCourse(
        @TargetEntityId String courseId,
        String title,
        int capacity,
        CourseType courseType,
        String typeData
) {
}
