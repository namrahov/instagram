package az.instagram.controller;

import az.instagram.dto.request.CreatePostRequest;
import az.instagram.dto.response.PostResponse;
import az.instagram.entity.PostEntity;
import az.instagram.security.CurrentUser;
import az.instagram.service.PostService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PostController {

    private final PostService posts;

    public PostController(PostService posts) {
        this.posts = posts;
    }

    /** Publishes one of the caller's verified uploads. */
    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public PostResponse create(@CurrentUser long currentUserId, @Valid @RequestBody CreatePostRequest request) {
        PostEntity post = posts.create(currentUserId, request.mediaId(), request.caption());
        return PostResponse.from(posts.get(currentUserId, post.getId()));
    }

    @GetMapping("/posts/{id}")
    public PostResponse get(@CurrentUser long currentUserId, @PathVariable long id) {
        return PostResponse.from(posts.get(currentUserId, id));
    }

    /** PUT/DELETE, not POST: liking is a state ("I like it"), so repeating the request changes nothing. */
    @PutMapping("/posts/{id}/like")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void like(@CurrentUser long currentUserId, @PathVariable long id) {
        posts.like(currentUserId, id);
    }

    @DeleteMapping("/posts/{id}/like")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlike(@CurrentUser long currentUserId, @PathVariable long id) {
        posts.unlike(currentUserId, id);
    }
}
