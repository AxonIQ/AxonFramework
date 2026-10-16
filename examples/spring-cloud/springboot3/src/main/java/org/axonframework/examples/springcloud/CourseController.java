/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.examples.springcloud;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

/**
 * REST endpoints fronting the distributed {@code courses} command and query handlers.
 * <p>
 * Deployed under the {@code portal} profile, which carries no Axon handlers of its own: every request is dispatched
 * through the Spring Cloud connector to whichever node registered as a handler.
 */
@RestController
@Profile("portal")
@RequestMapping("/courses")
class CourseController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    CourseController(CommandGateway commandGateway, QueryGateway queryGateway) {
        this.commandGateway = commandGateway;
        this.queryGateway = queryGateway;
    }

    @PostMapping
    CompletableFuture<Course> create(@RequestBody CreateCourse request) {
        return commandGateway.send(request).resultAs(Course.class);
    }

    @GetMapping("/{courseId}")
    CompletableFuture<Course> find(@PathVariable("courseId") String courseId) {
        return queryGateway.query(new FindCourse(courseId), Course.class);
    }

    @PutMapping("/{courseId}")
    CompletableFuture<Course> rename(@PathVariable("courseId") String courseId,
                                     @RequestBody RenameCourseRequest request) {
        return commandGateway.send(new RenameCourse(courseId, request.name())).resultAs(Course.class);
    }

    @GetMapping(path = "/{courseId}/subscription", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter subscribe(@PathVariable("courseId") String courseId) {
        SseEmitter emitter = new SseEmitter(0L);
        Publisher<Course> updates = queryGateway.subscriptionQuery(new FindCourse(courseId), Course.class, 16);

        Subscriber<Course> subscriber = new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                emitter.onCompletion(subscription::cancel);
                emitter.onTimeout(subscription::cancel);
                emitter.onError(ignored -> subscription.cancel());
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Course course) {
                try {
                    emitter.send(SseEmitter.event().name("course").data(course));
                } catch (Exception exception) {
                    emitter.completeWithError(exception);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                emitter.completeWithError(throwable);
            }

            @Override
            public void onComplete() {
                emitter.complete();
            }
        };

        updates.subscribe(subscriber);
        return emitter;
    }
}
