package com.redditclone.mail;

// Plain string-built HTML, no templating engine — this app has no templating dependency today and these
// three messages are simple enough not to justify adding one (see EmailOutboxWorker for the "kind" this
// maps from).
final class EmailTemplates {

    private EmailTemplates() {
    }

    static String verificationSubject() {
        return "Verify your email";
    }

    static String verificationHtml(String username, String link) {
        return """
                <p>Hi %s,</p>
                <p>Click the link below to verify your email address:</p>
                <p><a href="%s">%s</a></p>
                <p>This link expires in 24 hours. If you didn't create this account, you can ignore this email.</p>
                """.formatted(escape(username), link, link);
    }

    static String passwordResetSubject() {
        return "Reset your password";
    }

    static String passwordResetHtml(String username, String link) {
        return """
                <p>Hi %s,</p>
                <p>We received a request to reset your password. Click the link below to choose a new one:</p>
                <p><a href="%s">%s</a></p>
                <p>This link expires in 1 hour. If you didn't request this, you can ignore this email — your
                password will not be changed.</p>
                """.formatted(escape(username), link, link);
    }

    static String notificationSubject(String type) {
        return switch (type) {
            case "reply" -> "You have a new reply";
            case "mention" -> "You were mentioned";
            case "mod_invite" -> "You've been invited to moderate a community";
            default -> "You have a new notification";
        };
    }

    static String notificationHtml(String username, String link, String type) {
        // Same wrapper for every type; only the sentence differs. A moderator invitation names what is being asked of the
        // reader, since "you have a new notification" would not tell them it needs an answer.
        String message = "mod_invite".equals(type)
                ? "You've been invited to moderate a community. <a href=\"%s\">Open your notifications</a> to accept or decline."
                : "You have a new notification. <a href=\"%s\">View it</a>.";
        return ("""
                <p>Hi %s,</p>
                <p>""" + message + """
                </p>
                <p>You can turn off these emails anytime in Settings.</p>
                """).formatted(escape(username), link);
    }

    // The only untrusted input these templates interpolate is the username; link values are always
    // backend-built (frontendUrl + a UUID token), never user-supplied.
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
