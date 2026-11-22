package az.instagram.exception;

/** The file is not a playable video. Retrying cannot help, so the job fails at once (no retries). */
public class InvalidVideoException extends RuntimeException {

    public InvalidVideoException(String message) {
        super(message);
    }
}
