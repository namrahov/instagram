package az.instagram.entity;

public enum MediaStatus {
    /** Row created and upload URL issued; bytes may or may not be in storage yet. */
    PENDING_UPLOAD,
    /** Video verified in storage; a VideoProcessingRequested event is queued (outbox). */
    UPLOADED,
    /** A transcoding worker is producing the renditions. */
    PROCESSING,
    /** Ready to show: an image right after complete, a video once all renditions exist. */
    READY,
    /** Transcoding failed after retries (or the file is not a playable video). The event went to the DLT. */
    FAILED,
    /** The object did not match the declaration (size/type) and was deleted. Terminal. */
    REJECTED,
    /** The user cancelled the upload; uploaded parts were discarded. Terminal. */
    ABORTED,
    /** Nobody completed the upload in time; the cleanup job discarded it. Terminal. */
    EXPIRED;

    /** The original file was verified and is still in storage. */
    public boolean isVerified() {
        return this == UPLOADED || this == PROCESSING || this == READY || this == FAILED;
    }
}
