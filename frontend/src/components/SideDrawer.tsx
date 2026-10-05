import { useEffect, type ReactNode } from 'react';
import { NavLink, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import styles from './SideDrawer.module.css';

interface SideDrawerProps {
  open: boolean;
  onClose: () => void;
}

interface DrawerItem {
  label: string;
  to: string;
  icon: ReactNode;
  active: boolean;
}

const icon = (d: string) => (
  <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d={d} />
  </svg>
);

const ICONS = {
  home: icon('M3 11l9-8 9 8v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z'),
  popular: icon('M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM8.5 15.5l7-7M10 8.5h5.5V14'),
  hot: icon('M12 3c1 4 5 5.5 5 10a5 5 0 0 1-10 0c0-2 1-3 2-4 .5 1.5 1.5 2 2 2 0-3-1-5 1-8z'),
  new: icon('M12 3l2.2 5.3 5.8.5-4.4 3.8 1.4 5.6L12 15l-5 3.2 1.4-5.6L4 8.8l5.8-.5z'),
  top: icon('M5 21V10M12 21V4M19 21v-8'),
  explore: icon('M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM15.5 8.5l-2 5-5 2 2-5z'),
  plus: icon('M12 5v14M5 12h14'),
  chat: icon('M4 5h16v11H9l-5 4z'),
  bell: icon('M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 21h4'),
  settings: icon('M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.3.9a7 7 0 0 0-2-1.2L14.2 3h-4l-.4 2.6a7 7 0 0 0-2 1.2l-2.3-.9-2 3.4 2 1.5a7 7 0 0 0 0 2.4l-2 1.5 2 3.4 2.3-.9a7 7 0 0 0 2 1.2l.4 2.6h4l.4-2.6a7 7 0 0 0 2-1.2l2.3.9 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z'),
  user: icon('M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0'),
};

export function SideDrawer({ open, onClose }: SideDrawerProps) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const onHome = location.pathname === '/';
  const sort = searchParams.get('sort');
  const feed = (to: string, label: string, ic: ReactNode, match: boolean): DrawerItem => ({ to, label, icon: ic, active: onHome && match });

  const feeds: DrawerItem[] = [
    feed('/', 'Home', ICONS.home, !sort || sort === 'hot'),
    feed('/?sort=top&t=day', 'Popular', ICONS.popular, sort === 'top'),
    feed('/?sort=new', 'New', ICONS.new, sort === 'new'),
    feed('/?sort=rising', 'Rising', ICONS.hot, sort === 'rising'),
    feed('/?sort=controversial', 'Controversial', ICONS.top, sort === 'controversial'),
  ];

  const path = location.pathname;
  const community: DrawerItem[] = [
    { to: '/communities', label: 'Explore', icon: ICONS.explore, active: path === '/communities' },
    { to: '/communities/create', label: 'Start a community', icon: ICONS.plus, active: path === '/communities/create' },
  ];

  const account: DrawerItem[] = user
    ? [
        { to: '/chat', label: 'Chat', icon: ICONS.chat, active: path.startsWith('/chat') },
        { to: '/notifications', label: 'Notifications', icon: ICONS.bell, active: path === '/notifications' },
        { to: `/user/${user.username}`, label: 'Profile', icon: ICONS.user, active: path === `/user/${user.username}` },
        { to: '/scheduled', label: 'Scheduled posts', icon: ICONS.new, active: path === '/scheduled' },
        { to: '/settings', label: 'Settings', icon: ICONS.settings, active: path === '/settings' },
      ]
    : [];

  const renderSection = (title: string | null, items: DrawerItem[]) =>
    items.length > 0 && (
      <section className={styles.section}>
        {title && <h2 className={styles.heading}>{title}</h2>}
        {items.map((item) => (
          <NavLink
            key={item.label}
            to={item.to}
            className={`${styles.item} ${item.active ? styles.itemActive : ''}`}
            onClick={onClose}
          >
            {item.icon}
            <span>{item.label}</span>
          </NavLink>
        ))}
      </section>
    );

  return (
    <>
      <div className={`${styles.overlay} ${open ? styles.overlayOpen : ''}`} onClick={onClose} aria-hidden="true" />
      <nav className={`${styles.drawer} ${open ? styles.drawerOpen : ''}`} aria-label="Main navigation" aria-hidden={!open} inert={!open}>
        {renderSection(null, feeds)}
        {renderSection('Communities', community)}
        {renderSection('Your account', account)}
        {user && (
          <section className={styles.section}>
            <button
              type="button"
              className={styles.item}
              onClick={async () => {
                onClose();
                await logout();
                navigate('/');
              }}
            >
              <span>Log out</span>
            </button>
          </section>
        )}
      </nav>
    </>
  );
}
