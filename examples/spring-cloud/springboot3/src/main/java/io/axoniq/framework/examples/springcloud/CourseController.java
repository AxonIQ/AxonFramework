/*
 * Copyright (c) 2010-2026. AxonIQ B.V.
 *
 * Licensed under the AXONIQ TERMS OF SERVICE,
 * Version 29 April 2026 (the "License");
 *
 * The software is available for evaluation use without registration.
 * Continued use beyond the evaluation period requires registration
 * and a commercial license. See the License for the specific language
 * governing permissions and limitations under the License.
 * You may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at:
 *  https://www.axoniq.io/legal/terms-of-service
 *
 * For licensing information and to register, visit:
 *  https://www.axoniq.io/pricing
 */

package io.axoniq.framework.examples.springcloud;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.reactivestreams.Publisher;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

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
        return queryGateway.query(new FindCourse(courseId), Course.class, null);
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
