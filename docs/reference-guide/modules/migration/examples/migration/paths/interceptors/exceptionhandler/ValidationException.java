package migration.paths.interceptors.exceptionhandler;

public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
