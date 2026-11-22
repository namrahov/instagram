package az.instagram.entity;

public enum MediaStatus {
    /** Row created and upload URL issued; bytes may or may not be in storage yet. */
    PENDING_UPLOAD,
    /** The backend checked the object in storage and it matches what the client declared. */
    UPLOADED,
    /** The object did not match the declaration (size/type) and was deleted. Terminal. */
    REJECTED,
    /** The user cancelled the upload; uploaded parts were discarded. Terminal. */
    ABORTED,
    /** Nobody completed the upload in time; the cleanup job discarded it. Terminal. */
    EXPIRED
}
