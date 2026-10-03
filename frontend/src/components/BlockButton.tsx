import { useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../lib/apiClient';
import { blockUser, fetchBlockStatus, unblockUser } from '../lib/userApi';
import styles from './BlockButton.module.css';

interface BlockButtonProps {
  username: string;
  isOwnProfile: boolean;
  // Called after a successful block/unblock so the parent can refresh anything that depends on it
  // (blocking also severs follows, so follower counts shown on the profile may have changed).
  onChanged?: () => void;
}

export function BlockButton({ username, isOwnProfile, onChanged }: BlockButtonProps) {
  const { user } = useAuth();
  const [blocked, setBlocked] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!user || isOwnProfile) return;
    let cancelled = false;
    fetchBlockStatus(username)
      .then((r) => {
        if (!cancelled) setBlocked(r.isBlocked);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [user, username, isOwnProfile]);

  if (!user || isOwnProfile || blocked === null) return null;

  const toggle = async () => {
    if (!blocked && !window.confirm(`Block u/${username}? They won't be able to reply to you or message you, and you won't see their posts in feeds.`)) return;
    setBusy(true);
    setError(null);
    try {
      if (blocked) await unblockUser(username);
      else await blockUser(username);
      setBlocked(!blocked);
      onChanged?.();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not update the block.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <button type="button" className={styles.button} disabled={busy} onClick={toggle}>
        {blocked ? 'Unblock' : 'Block'}
      </button>
      {error && <span className={styles.error}>{error}</span>}
    </>
  );
}
