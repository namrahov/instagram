package az.instagram.controller;

import az.instagram.dto.request.InitiateUploadRequest;
import az.instagram.dto.request.PartUrlsRequest;
import az.instagram.dto.response.InitiateUploadResponse;
import az.instagram.dto.response.MediaResponse;
import az.instagram.dto.response.PartUrlResponse;
import az.instagram.dto.response.UploadProgressResponse;
import az.instagram.entity.MediaEntity;
import az.instagram.security.CurrentUser;
import az.instagram.service.MediaUploadService;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /**
     * Step 1: start an upload. Small files get one presigned PUT URL ({@code SINGLE}); large videos get a part
     * layout ({@code MULTIPART}). Either way the bytes go straight to storage, not through this service.
     */
    @PostMapping("/media/uploads/initiate")
    @ResponseStatus(HttpStatus.CREATED)
    public InitiateUploadResponse initiate(@CurrentUser long currentUserId,
                                           @Valid @RequestBody InitiateUploadRequest request) {
        return InitiateUploadResponse.from(uploads.initiate(currentUserId, request.contentType(), request.sizeBytes()));
    }

    /** Multipart: presigned URLs for the given parts. Ask again for fresh URLs when they expire. Owner only. */
    @PostMapping("/media/uploads/{id}/parts/urls")
    public List<PartUrlResponse> partUrls(@CurrentUser long currentUserId, @PathVariable long id,
                                          @Valid @RequestBody PartUrlsRequest request) {
        return uploads.partUrls(currentUserId, id, request.partNumbers()).stream().map(PartUrlResponse::from).toList();
    }

    /** Multipart resume: which parts storage already has and which are missing. Owner only. */
    @GetMapping("/media/uploads/{id}/parts")
    public UploadProgressResponse progress(@CurrentUser long currentUserId, @PathVariable long id) {
        return UploadProgressResponse.from(uploads.progress(currentUserId, id));
    }

    /** Cancel an unfinished upload and free its storage. Owner only. */
    @DeleteMapping("/media/uploads/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abort(@CurrentUser long currentUserId, @PathVariable long id) {
        uploads.abort(currentUserId, id);
    }

    /**
     * Last step: ask the backend to verify the upload (multipart: join the parts first).
     * Owner only (404 otherwise).
     */
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
        return MediaResponse.from(media, uploads.downloadUrl(media).orElse(null), uploads.variants(media));
    }
}
