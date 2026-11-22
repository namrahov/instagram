package az.instagram.controller;

import az.instagram.dto.request.InitiateUploadRequest;
import az.instagram.dto.response.InitiateUploadResponse;
import az.instagram.dto.response.MediaResponse;
import az.instagram.entity.MediaEntity;
import az.instagram.security.CurrentUser;
import az.instagram.service.MediaUploadService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MediaController {

    private final MediaUploadService uploads;

    public MediaController(MediaUploadService uploads) {
        this.uploads = uploads;
    }

    /** Step 1: get a presigned URL. The bytes go straight to storage, not through this service. */
    @PostMapping("/media/uploads/initiate")
    @ResponseStatus(HttpStatus.CREATED)
    public InitiateUploadResponse initiate(@CurrentUser long currentUserId,
                                           @Valid @RequestBody InitiateUploadRequest request) {
        return InitiateUploadResponse.from(uploads.initiate(currentUserId, request.contentType(), request.sizeBytes()));
    }

    /** Step 3: after the PUT to storage, ask the backend to verify the object. Owner only (404 otherwise). */
    @PostMapping("/media/uploads/{id}/complete")
    public MediaResponse complete(@CurrentUser long currentUserId, @PathVariable long id) {
        return toResponse(uploads.complete(currentUserId, id));
    }

    /** Owner only (404 otherwise). */
    @GetMapping("/media/{id}")
    public MediaResponse get(@CurrentUser long currentUserId, @PathVariable long id) {
        return toResponse(uploads.getOwned(currentUserId, id));
    }

    private MediaResponse toResponse(MediaEntity media) {
        return MediaResponse.from(media, uploads.downloadUrl(media).orElse(null));
    }
}
