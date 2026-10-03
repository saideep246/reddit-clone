import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { BlockedUsersSection } from '../components/BlockedUsersSection';
import { NotificationPrefsForm } from '../components/NotificationPrefsForm';
import { ApiError } from '../lib/apiClient';
import { deleteAccount } from '../lib/settingsApi';
import { useSettings } from '../settings/SettingsContext';
import type { Theme } from '../settings/SettingsContext';
import styles from './Settings.module.css';

const JOIN_DATE_FORMAT = new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric' });

export function Settings() {
  const { user, logout } = useAuth();
  const { settings, updateNsfwBlur, updatePrivacyPrefs, theme, setTheme } = useSettings();
  const navigate = useNavigate();

  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [password, setPassword] = useState('');
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  const restrictChatToKnown = settings?.privacyPrefs.restrictChatToKnown === true;

  const handleDelete = async (e: FormEvent) => {
    e.preventDefault();
    setDeleting(true);
    setDeleteError(null);
    try {
      await deleteAccount(password);
      await logout();
      navigate('/');
    } catch (err) {
      setDeleteError(err instanceof ApiError && err.status === 401 ? 'Incorrect password.' : 'Could not delete your account.');
    } finally {
      setDeleting(false);
    }
  };

  if (!user) return null;

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Settings</h1>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Blocked users</h2>
        <BlockedUsersSection />
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Account</h2>
        <dl className={styles.accountInfo}>
          <dt>Username</dt>
          <dd>u/{user.username}</dd>
          <dt>Email</dt>
          <dd>{user.email}</dd>
          <dt>Karma</dt>
          <dd>{user.karmaPost + user.karmaComment}</dd>
          <dt>Member since</dt>
          <dd>{JOIN_DATE_FORMAT.format(new Date(user.createdAt))}</dd>
        </dl>
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Privacy</h2>
        <label className={styles.checkboxRow}>
          <input
            type="checkbox"
            checked={settings?.nsfwBlur ?? true}
            disabled={!settings}
            onChange={(e) => updateNsfwBlur(e.target.checked)}
          />
          Blur NSFW content by default
        </label>
        <label className={styles.checkboxRow}>
          <input
            type="checkbox"
            checked={restrictChatToKnown}
            disabled={!settings}
            onChange={(e) => updatePrivacyPrefs({ restrictChatToKnown: e.target.checked })}
          />
          Only let people I've already talked to message me
        </label>
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Notifications</h2>
        <NotificationPrefsForm />
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Theme</h2>
        <div className={styles.themeOptions}>
          {(['system', 'light', 'dark'] as const).map((option) => (
            <label key={option} className={styles.themeOption}>
              <input
                type="radio"
                name="theme"
                checked={option === 'system' ? theme === undefined : theme === option}
                onChange={() => setTheme(option === 'system' ? undefined : (option as Theme))}
              />
              {option === 'system' ? 'Match system' : option === 'light' ? 'Light' : 'Dark'}
            </label>
          ))}
        </div>
      </section>

      <section className={`${styles.section} ${styles.dangerZone}`}>
        <h2 className={styles.sectionTitle}>Danger zone</h2>
        {!confirmingDelete ? (
          <button type="button" className={styles.deleteButton} onClick={() => setConfirmingDelete(true)}>
            Delete account
          </button>
        ) : (
          <form className={styles.deleteForm} onSubmit={handleDelete}>
            <p className={styles.deleteWarning}>
              This will permanently sign you out of every device and anonymize your account. Enter your password to confirm.
            </p>
            <input
              className={styles.passwordInput}
              type="password"
              placeholder="Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoFocus
            />
            {deleteError && <div className={styles.deleteError}>{deleteError}</div>}
            <div className={styles.deleteActions}>
              <button type="submit" className={styles.deleteButton} disabled={deleting || !password}>
                {deleting ? 'Deleting…' : 'Confirm deletion'}
              </button>
              <button
                type="button"
                className={styles.cancelButton}
                onClick={() => {
                  setConfirmingDelete(false);
                  setPassword('');
                  setDeleteError(null);
                }}
              >
                Cancel
              </button>
            </div>
          </form>
        )}
      </section>
    </div>
  );
}
