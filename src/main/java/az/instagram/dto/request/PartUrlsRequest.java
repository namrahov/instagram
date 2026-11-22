package az.instagram.dto.request;

import az.instagram.service.MediaUploadService;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Part numbers (1-based) the client wants to upload next, e.g. the missing ones after a resume. */
public record PartUrlsRequest(
        @NotEmpty @Size(max = MediaUploadService.MAX_PART_URLS_PER_REQUEST)
        List<@NotNull Integer> partNumbers
) {
}
