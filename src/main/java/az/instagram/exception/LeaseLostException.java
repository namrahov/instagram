package az.instagram.exception;

/**
 * The worker's lease on a rendition was taken over (or released by the sweeper) while ffmpeg was running.
 * Not an error of the job: someone else now owns that rendition. The worker drops its partial work and moves on.
 */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(String message) {
        super(message);
    }
}
