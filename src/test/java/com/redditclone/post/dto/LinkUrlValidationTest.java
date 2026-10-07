package com.redditclone.post.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkUrlValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static CreatePostRequest request(String kind, String url) {
        return new CreatePostRequest(kind, "title", "text".equals(kind) ? "body" : null, url,
                null, null, null, null, null, null, null, null);
    }

    private static boolean violates(CreatePostRequest req, String messageFragment) {
        return validator.validate(req).stream().anyMatch(v -> v.getMessage().contains(messageFragment));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/",
            "http://example.com",
            "HTTPS://Example.com/Path?q=1#frag",
            "https://www.example.com:8443/a/b",
            "http://127.0.0.1:8080/x"
    })
    void acceptsHttpAndHttpsUrls(String url) {
        assertTrue(LinkUrls.isHttpUrl(url));
        assertTrue(validator.validate(request("link", url)).isEmpty(), url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "ftp://example.com/file",
            "//example.com/protocol-relative",
            "/relative/path",
            "example.com",
            "http://",
            "https:///no-host",
            "https://exa mple.com",
            " https://example.com",
            "https://example.com ",
            "https://example.com/\npath"
    })
    void rejectsNonHttpUrls(String url) {
        assertFalse(LinkUrls.isHttpUrl(url), url);
        assertTrue(violates(request("link", url), "http:// or https://"), url);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void blankUrlIsRejectedForLinkPostsByTheRequiredRule(String url) {
        assertFalse(LinkUrls.isHttpUrl(url));
        assertTrue(violates(request("link", url), "url is required for kind=link"));
        // the scheme rule defers to the required rule so a blank url yields one clear message, not two
        assertFalse(violates(request("link", url), "http:// or https://"));
    }

    @Test
    void schemeRuleOnlyAppliesToLinkPosts() {
        assertFalse(violates(request("text", null), "http:// or https://"));
    }
}
