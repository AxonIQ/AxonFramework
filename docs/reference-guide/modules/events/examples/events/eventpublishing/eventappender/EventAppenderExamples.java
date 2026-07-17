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
package events.eventpublishing.eventappender;

// The import is indented to the depth of the nested method below, so that the
// indent=0 normalization of the include renders both regions flush left.
// tag::append-event-with-metadata-import[]
    import org.axonframework.messaging.core.Metadata;

// end::append-event-with-metadata-import[]
import java.util.List;
import java.util.Map;

// tag::append-event-from-command-handler[]
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

class GiftCard {

    @CommandHandler
    public static GiftCard create(IssueCardCommand cmd, EventAppender appender) {
        appender.append(new CardIssuedEvent(cmd.cardId(), cmd.amount(), cmd.shopId()));
        return new GiftCard();
    }
    // omitted state, command and event sourcing handlers
}
// end::append-event-from-command-handler[]

record IssueCardCommand(String cardId, int amount, String shopId) {

}

record CardIssuedEvent(String cardId, int amount, String shopId) {

}

record CardActivatedEvent(String cardId) {

}

class GiftCardAppendMultiple {

    // tag::append-multiple-events[]
    @CommandHandler
    public static GiftCard create(IssueCardCommand cmd, EventAppender appender) {
        appender.append(
                new CardIssuedEvent(cmd.cardId(), cmd.amount(), cmd.shopId()),
                new CardActivatedEvent(cmd.cardId())
        );
        return new GiftCard();
    }
    // end::append-multiple-events[]
}

class GiftCardAppendMetadata {

    // tag::append-event-with-metadata[]
    @CommandHandler
    public static GiftCard create(IssueCardCommand cmd, EventAppender appender) {
        Metadata metadata = Metadata.from(Map.of("issuedBy", "admin-service"));

        // Single event with metadata
        appender.append(new CardIssuedEvent(cmd.cardId(), cmd.amount(), cmd.shopId()), metadata);

        return new GiftCard();
    }
    // end::append-event-with-metadata[]
}

class GiftCardAppendMetadataBatch {

    // tag::append-batch-with-metadata[]
    @CommandHandler
    public static GiftCard create(IssueCardCommand cmd, EventAppender appender) {
        Metadata metadata = Metadata.from(Map.of("issuedBy", "admin-service"));

        appender.append(
                List.of(
                        new CardIssuedEvent(cmd.cardId(), cmd.amount(), cmd.shopId()),
                        new CardActivatedEvent(cmd.cardId())
                ),
                metadata
        );
        return new GiftCard();
    }
    // end::append-batch-with-metadata[]
}
