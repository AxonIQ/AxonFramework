package commands.commanddispatchers;

record RedeemCardCommand(String cardId, int amount) {
}

record CardRedeemedEvent(String cardId, int amount) {
}

record SendThankYouEmailCommand(String cardId) {
}
