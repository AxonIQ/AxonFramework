package commands.configuration.handlers;

class GiftCard {

    private boolean cancelled;

    boolean canBeCancelled() {
        return !cancelled;
    }
}
