package com.redditclone.auth;

// Read by notify.NotificationOutboxWorker via AuthService.findEmailRecipient — carries just enough to
// address an outbox "email" event without notify reaching into UserRepository directly (that repository
// access is confined to auth/common by ModuleBoundaryTest).
public record EmailRecipient(String email, String username) {
}
