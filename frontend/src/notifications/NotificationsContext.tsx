import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthContext';
import { fetchMyModeratorInvites } from '../lib/moderatorInviteApi';
import { fetchNotifications, markAllNotificationsRead, markNotificationRead } from '../lib/notificationApi';
import type { MyModeratorInvite } from '../types/moderation';
import type { NotificationItem } from '../types/notification';

// No STOMP push exists for notifications today (chat's /queue infra is reused for chat_message delivery,
// but nothing calls convertAndSendToUser for notifications) — this polls instead, only while logged in.
const POLL_INTERVAL_MS = 30_000;

interface NotificationsContextValue {
  notifications: NotificationItem[];
  unreadCount: number;
  // Pending moderator invitations, fetched separately from the notification rows so they stay discoverable even when the
  // user has muted the mod_invite notification type.
  pendingInvites: MyModeratorInvite[];
  // What the bell shows: unread rows plus pending invitations that have no unread row of their own (no double counting).
  badgeCount: number;
  refreshInvites: () => Promise<void>;
  loading: boolean;
  refresh: () => Promise<void>;
  markRead: (id: string) => Promise<void>;
  markAllRead: () => Promise<void>;
}

const NotificationsContext = createContext<NotificationsContextValue | null>(null);

export function NotificationsProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [pendingInvites, setPendingInvites] = useState<MyModeratorInvite[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setNotifications(await fetchNotifications());
    } finally {
      setLoading(false);
    }
  }, []);

  const refreshInvites = useCallback(async () => {
    try {
      setPendingInvites(await fetchMyModeratorInvites());
    } catch {
      // Keep whatever was shown; the next poll tries again.
    }
  }, []);

  useEffect(() => {
    if (!user) {
      setNotifications([]);
      setPendingInvites([]);
      return;
    }
    const tick = () => {
      refresh();
      refreshInvites();
    };
    tick();
    const interval = setInterval(tick, POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [user, refresh, refreshInvites]);

  // Optimistic local update, same shape as F8's useModQueue.dropFromQueue — the server call already
  // succeeded by the time this runs, so there's no need to refetch the whole list for one flipped flag.
  const markRead = useCallback(async (id: string) => {
    await markNotificationRead(id);
    setNotifications((prev) => prev.map((n) => (n.id === id ? { ...n, readAt: n.readAt ?? new Date().toISOString() } : n)));
  }, []);

  const markAllRead = useCallback(async () => {
    await markAllNotificationsRead();
    const now = new Date().toISOString();
    setNotifications((prev) => prev.map((n) => (n.readAt ? n : { ...n, readAt: now })));
  }, []);

  const unreadCount = notifications.filter((n) => !n.readAt).length;
  const unreadInviteIds = new Set(notifications.filter((n) => n.type === 'mod_invite' && !n.readAt).map((n) => n.source.inviteId));
  const badgeCount = unreadCount + pendingInvites.filter((i) => !unreadInviteIds.has(i.id)).length;

  return (
    <NotificationsContext.Provider value={{ notifications, unreadCount, pendingInvites, badgeCount, refreshInvites, loading, refresh, markRead, markAllRead }}>
      {children}
    </NotificationsContext.Provider>
  );
}

export function useNotifications() {
  const ctx = useContext(NotificationsContext);
  if (!ctx) throw new Error('useNotifications must be used within NotificationsProvider');
  return ctx;
}
