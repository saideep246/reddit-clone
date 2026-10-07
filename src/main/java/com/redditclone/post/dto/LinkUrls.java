package com.redditclone.post.dto;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

// The one rule for what may be stored as a link post's url: an absolute http(s) URL with a host. Anything else
// (javascript:, data:, file:, relative paths, "http://" with no host) is rejected so a stored value is always safe
// to put in an <a href>. Shared by post creation (CreatePostRequest) and the title/link edit path (PostService).
public final class LinkUrls {

    private LinkUrls() {
    }

    public static boolean isHttpUrl(String url) {
        if (url == null || url.isBlank() || !url.equals(url.strip()) || url.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return scheme != null
                    && ("http".equals(scheme.toLowerCase(Locale.ROOT)) || "https".equals(scheme.toLowerCase(Locale.ROOT)))
                    && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
