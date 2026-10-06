package com.redditclone.media;

import com.redditclone.media.dto.UploadUrlRequest;
import com.redditclone.media.dto.UploadUrlResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/api/media/upload-url")
    public UploadUrlResponse requestUploadUrl(@AuthenticationPrincipal UUID userId, @Valid @RequestBody UploadUrlRequest req) {
        return mediaService.requestUploadUrl(userId, req.filename(), req.contentType(), req.byteSize());
    }

    // Public, like the media URLs themselves (knowing a random id reveals nothing the public URLs don't). The website
    // polls this for a freshly uploaded file until its processing status is 'ready' (or 'failed').
    @GetMapping("/api/media/{id}")
    public MediaView view(@PathVariable UUID id) {
        return mediaService.getView(id);
    }

    @PostMapping("/api/media/{id}/complete")
    public void complete(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        mediaService.completeUpload(id, userId);
    }
}
