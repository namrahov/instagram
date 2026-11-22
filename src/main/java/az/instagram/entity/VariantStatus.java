package az.instagram.entity;

public enum VariantStatus {
    /** Nobody works on this rendition. */
    PENDING,
    /** A worker holds the lease (locked_by, lease_until) and the current generation. */
    PROCESSING,
    /** The file is in storage under object_key. */
    COMPLETED
}
