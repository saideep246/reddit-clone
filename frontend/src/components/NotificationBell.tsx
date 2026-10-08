import { Link } from 'react-router-dom';
import { useNotifications } from '../notifications/NotificationsContext';
import styles from './NotificationBell.module.css';

export function NotificationBell() {
  const { badgeCount } = useNotifications();

  return (
    <Link to="/notifications" className={styles.bell} aria-label="Notifications">
      <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
        <path d="M12 2a6 6 0 0 0-6 6v3.09c0 .47-.16.93-.46 1.3L4 14.5c-.76.94-.1 2.5 1.1 2.5h13.8c1.2 0 1.86-1.56 1.1-2.5l-1.54-2.11c-.3-.37-.46-.83-.46-1.3V8a6 6 0 0 0-6-6z" />
        <path d="M9.5 19a2.5 2.5 0 0 0 5 0h-5z" />
      </svg>
      {badgeCount > 0 && <span className={styles.badge}>{badgeCount > 99 ? '99+' : badgeCount}</span>}
    </Link>
  );
}
