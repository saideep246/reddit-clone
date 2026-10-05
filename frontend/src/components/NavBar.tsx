import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ChatNavLink } from './ChatNavLink';
import { CreateMenu } from './CreateMenu';
import { Logo } from './Logo';
import { NotificationBell } from './NotificationBell';
import { SettingsNavLink } from './SettingsNavLink';
import { SideDrawer } from './SideDrawer';
import styles from './NavBar.module.css';

export function NavBar() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [query, setQuery] = useState('');

  // On the search page the box mirrors the page's own query (so a reload, a shared link or the page's own
  // input all leave the navbar showing what is being searched).
  useEffect(() => {
    if (location.pathname === '/search') {
      setQuery(new URLSearchParams(location.search).get('q') ?? '');
    }
  }, [location.pathname, location.search]);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const closeDrawer = useCallback(() => setDrawerOpen(false), []);

  const handleLogout = async () => {
    await logout();
    navigate('/');
  };

  const handleSearch = (e: FormEvent) => {
    e.preventDefault();
    navigate(query.trim() ? `/search?q=${encodeURIComponent(query.trim())}` : '/communities');
  };

  return (
    <header className={styles.nav}>
      <button
        type="button"
        className={styles.menuButton}
        aria-label="Open menu"
        aria-expanded={drawerOpen}
        onClick={() => setDrawerOpen((o) => !o)}
      >
        <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
          <path d="M4 6h16M4 12h16M4 18h16" />
        </svg>
      </button>
      <SideDrawer open={drawerOpen} onClose={closeDrawer} />

      <Link to="/" className={styles.logoLink}>
        <Logo />
      </Link>

      <Link to="/communities" className={styles.communitiesLink}>
        Communities
      </Link>

      <form className={styles.search} onSubmit={handleSearch}>
        <input
          className={styles.searchInput}
          type="search"
          placeholder="Search reddit"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </form>

      <div className={styles.actions}>
        {user ? (
          <div className={styles.userMenu}>
            <CreateMenu />
            <ChatNavLink />
            <NotificationBell />
            <SettingsNavLink />
            <Link to={`/user/${user.username}`} className={styles.profileLink}>
              <span className={styles.avatar}>{user.username.slice(0, 1)}</span>
              <span className={styles.username}>{user.username}</span>
            </Link>
            <button type="button" className={styles.logoutButton} onClick={handleLogout}>
              Log Out
            </button>
          </div>
        ) : (
          <>
            <Link to="/login" className={styles.buttonSecondary}>
              Log In
            </Link>
            <Link to="/register" className={styles.buttonPrimary}>
              Sign Up
            </Link>
          </>
        )}
      </div>
    </header>
  );
}
