package messagingconcepts.componentmessageintercepting;

public class SecurityContext {

    public boolean isAuthorized(Object command) {
        return true;
    }

    public boolean canQuery() {
        return true;
    }

    public String currentUser() {
        return "user";
    }
}
