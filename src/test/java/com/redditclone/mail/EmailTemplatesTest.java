package com.redditclone.mail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailTemplatesTest {

    @Test
    void moderatorInviteEmailSaysSoAndLinksToNotifications() {
        assertEquals("You've been invited to moderate a community", EmailTemplates.notificationSubject("mod_invite"));
        String html = EmailTemplates.notificationHtml("sam", "https://app.example/notifications", "mod_invite");
        assertTrue(html.contains("invited to moderate a community"), html);
        assertTrue(html.contains("href=\"https://app.example/notifications\""), html);
        assertTrue(html.contains("Hi sam,"), html);
    }

    @Test
    void otherTypesKeepTheGenericWording() {
        assertEquals("You have a new reply", EmailTemplates.notificationSubject("reply"));
        assertEquals("You have a new notification", EmailTemplates.notificationSubject("chat_message"));
        String html = EmailTemplates.notificationHtml("sam", "https://app.example/notifications", "reply");
        assertTrue(html.contains("You have a new notification."), html);
        assertFalse(html.contains("moderate"), html);
    }

    @Test
    void theUsernameIsEscaped() {
        assertTrue(EmailTemplates.notificationHtml("<b>x</b>", "l", "mod_invite").contains("Hi &lt;b&gt;x&lt;/b&gt;,"));
    }
}
