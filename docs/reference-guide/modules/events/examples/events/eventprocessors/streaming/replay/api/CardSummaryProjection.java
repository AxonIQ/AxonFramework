package events.eventprocessors.streaming.replay.api;

// tag::replay-api[]
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.replay.ResetContext;
import org.axonframework.messaging.eventhandling.replay.ReplayStatus;
import org.axonframework.messaging.eventhandling.replay.annotation.AllowReplay;
import org.axonframework.messaging.eventhandling.replay.annotation.DisallowReplay;
import org.axonframework.messaging.eventhandling.replay.annotation.ReplayContext;
import org.axonframework.messaging.eventhandling.replay.annotation.ResetHandler;

@AllowReplay // <1>
@Namespace("card-summary")
public class CardSummaryProjection {
    //...
    @EventHandler
    @DisallowReplay // <2>
    public void on(CardIssuedEvent event) {
        // This event handler performs a "side effect",
        //  like sending an e-mail or a sms.
        // Neither, is something we want to reoccur when a
        //  replay happens, hence we disallow this method
        //  to be replayed
    }

    @EventHandler
    public void on(CardRedeemedEvent event, ReplayStatus replayStatus) { // <3>
        // We can wire a ReplayStatus here so we can see whether this
        // event is delivered to our handler as a 'REGULAR' event or
        // a 'REPLAY' event
        // Perform event handling
    }

    @ResetHandler // <4>
    public void onReset(ResetContext resetContext) {
        // Do pre-reset logic, like clearing out the projection table for a
        // clean slate. The given resetContext is [optional], allowing the
        // user to specify in what context a reset was executed.
    }

    @EventHandler
    public void on(CardCancelledEvent event, @ReplayContext CardReplayContext context) { // <5>
        // During replays, this method will get the CardReplayContext injected that was providing during the reset call.
        // If there is no replay, no context was supplied or the context type does not match, the parameter is null.
    }
    //...
}
// end::replay-api[]

record CardIssuedEvent(String cardId) {

}

record CardRedeemedEvent(String cardId) {

}

record CardCancelledEvent(String cardId) {

}

record CardReplayContext(String reason) {

}
