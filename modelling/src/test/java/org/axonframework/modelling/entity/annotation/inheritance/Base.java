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

package org.axonframework.modelling.entity.annotation.inheritance;

import org.axonframework.modelling.entity.domain.todo.commands.FinishTodoItem;

/**
 * Root entity fixture that holds common state fields ({@code id}, {@code handledBy},
 * {@code finished}) used by all inheritance test fixtures. The {@code handledBy} field
 * records which handler method was last invoked, allowing tests to assert whether the
 * subtype or the parent handler ran.
 */
public class Base {

    protected String id;
    protected String handledBy = "none";
    protected boolean finished;

    public void handle(FinishTodoItem command) {
        this.finished = true;
    }

    public String getId() {
        return id;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public boolean isFinished() {
        return finished;
    }
}
