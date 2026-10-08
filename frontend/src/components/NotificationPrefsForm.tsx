import { useState } from 'react';
import { useSettings } from '../settings/SettingsContext';
import type { NotificationType } from '../types/notification';
import styles from './NotificationPrefsForm.module.css';

const MUTE_TYPES: { type: NotificationType; label: string }[] = [
  { type: 'post_reply', label: 'Post replies' },
  { type: 'reply', label: 'Comment replies' },
  { type: 'mention', label: 'Mentions' },
  { type: 'chat_message', label: 'Chat messages' },
  { type: 'new_follower', label: 'New followers' },
  { type: 'mod_invite', label: 'Moderator invitations' },
];

// Shared by NotificationsInbox (F9) and the Settings page (F11) so the one checkbox list has one
// implementation, backed by the shared SettingsContext rather than each page's own local fetch.
export function NotificationPrefsForm() {
  const { settings, updateNotificationPrefs } = useSettings();
  const [error, setError] = useState<string | null>(null);
  const prefs = settings?.notificationPrefs ?? null;

  const toggle = async (type: NotificationType, enabled: boolean) => {
    setError(null);
    try {
      await updateNotificationPrefs({ [type]: enabled });
    } catch {
      setError('Could not save that setting.');
    }
  };

  return (
    <div>
      {error && <div className={styles.error}>{error}</div>}
      <div className={styles.list}>
        {MUTE_TYPES.map(({ type, label }) => (
          <label key={type} className={styles.item}>
            <input
              type="checkbox"
              checked={prefs ? prefs[type] !== false : true}
              disabled={!settings}
              onChange={(e) => toggle(type, e.target.checked)}
            />
            {label}
          </label>
        ))}
      </div>
    </div>
  );
}
