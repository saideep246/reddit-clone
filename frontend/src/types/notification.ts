export type NotificationType = 'post_reply' | 'reply' | 'mention' | 'chat_message' | 'new_follower' | 'mod_invite' | 'posting_request' | 'posting_decision';

// Flat/optional rather than a strict per-type union: the only consumer (NotificationsInbox) just reads
// whichever fields a given type happens to carry, so a cast-free optional-fields shape is simpler than a
// discriminated union here (postId/communityId for post_reply|reply|mention, roomId/senderId for
// chat_message — see CommentService.notifyFanOut/notifyMentions and ChatStompHandler for what's written).
export interface NotificationSource {
  actorId?: string;
  postId?: string;
  communityId?: string;
  commentId?: string;
  roomId?: string;
  senderId?: string;
  inviteId?: string;
  decision?: 'approved' | 'denied';
}

// Notification.source is stored/returned as a JSON *string* (a plain String + jsonb column on the
// backend) — parsed into a real object by lib/notificationApi.ts, same raw-string-to-typed-object split
// as types/moderation.ts's RawAutomodRule/AutomodRule.
export interface RawNotification {
  id: string;
  createdAt: string;
  type: NotificationType;
  source: string;
  readAt: string | null;
  postTitle: string | null;
  communityName: string | null;
  actorUsername: string | null;
}

export interface NotificationItem extends Omit<RawNotification, 'source'> {
  source: NotificationSource;
}
