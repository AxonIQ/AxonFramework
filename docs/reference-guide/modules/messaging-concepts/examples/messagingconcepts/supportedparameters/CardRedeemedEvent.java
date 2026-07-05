package messagingconcepts.supportedparameters;

public class CardRedeemedEvent {

    private final String cardId;
    private final int remainingValue;

    public CardRedeemedEvent(String cardId, int remainingValue) {
        this.cardId = cardId;
        this.remainingValue = remainingValue;
    }

    public String getCardId() {
        return cardId;
    }

    public int getRemainingValue() {
        return remainingValue;
    }
}
