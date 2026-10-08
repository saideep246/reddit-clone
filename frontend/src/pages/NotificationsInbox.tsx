import { Link } from 'react-router-dom';
import { NotificationPrefsForm } from '../components/NotificationPrefsForm';
import { PendingModeratorInvites } from '../components/PendingModeratorInvites';
import { decodeHtmlEntities } from '../lib/html';
import { timeAgo } from '../lib/time';
import { useNotifications } from '../notifications/NotificationsContext';
import type { NotificationItem } from '../types/notification';
import styles from './NotificationsInbox.module.css';

// chat_message never links anywhere — chat has no frontend page yet (that's F10) — and reply/mention
// also only link to the post itself, not a specific comment: PostDetail has no comment-anchor/scroll-to
// feature today, same "no link for those today" limitation F8 already accepted for reported comments.
function describe(n: NotificationItem, pendingInviteIds: Set<string>): { text: string; href: string | null } {
  const actor = n.actorUsername ?? '[deleted]';
  const postTitle = decodeHtmlEntities(n.postTitle) ?? '[deleted]';
  const postHref = n.communityName && n.source.postId ? `/r/${n.communityName}/comments/${n.source.postId}` : null;
  switch (n.type) {
    case 'post_reply':
      return { text: `u/${actor} commented on your post "${postTitle}"`, href: postHref };
    case 'reply':
      return { text: `u/${actor} replied to your comment on "${postTitle}"`, href: postHref };
    case 'mention':
      return { text: `u/${actor} mentioned you on "${postTitle}"`, href: postHref };
    case 'chat_message':
      return { text: `u/${actor} sent you a message`, href: null };
    case 'new_follower':
      return { text: `u/${actor} started following you`, href: n.actorUsername ? `/user/${actor}` : null };
    case 'mod_invite': {
      // The "how to respond" hint only while the invitation is still pending; afterwards the row is just a record.
      const where = n.communityName ? ` of r/${n.communityName}` : '';
      const hint = n.source.inviteId && pendingInviteIds.has(n.source.inviteId) ? ' Accept or decline under Moderator invitations.' : '';
      return { text: `u/${actor} invited you to become a moderator${where}.${hint}`, href: null };
    }
    default:
      return { text: 'New notification', href: null };
  }
}

export function NotificationsInbox() {
  const { notifications, unreadCount, pendingInvites, loading, markRead, markAllRead } = useNotifications();
  const pendingInviteIds = new Set(pendingInvites.map((i) => i.id));

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Notifications</h1>
        <button type="button" className={styles.markAllButton} disabled={unreadCount === 0} onClick={() => markAllRead()}>
          Mark all as read
        </button>
      </div>

      <PendingModeratorInvites />

      <section className={styles.prefs}>
        <h2 className={styles.prefsTitle}>Notify me about</h2>
        <NotificationPrefsForm />
      </section>

      {loading && notifications.length === 0 ? (
        <div className={styles.state}>Loading…</div>
      ) : notifications.length === 0 ? (
        <div className={styles.state}>You're all caught up.</div>
      ) : (
        <div className={styles.list}>
          {notifications.map((n) => {
            const { text, href } = describe(n, pendingInviteIds);
            const rowClass = `${styles.row} ${!n.readAt ? styles.unread : ''}`;
            const row = (
              <>
                <p className={styles.text}>{text}</p>
                <span className={styles.time}>{timeAgo(n.createdAt)}</span>
              </>
            );
            return href ? (
              <Link key={n.id} to={href} className={rowClass} onClick={() => markRead(n.id)}>
                {row}
              </Link>
            ) : (
              <div key={n.id} className={rowClass}>
                {row}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
