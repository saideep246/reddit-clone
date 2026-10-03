import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchBlockedUsers, unblockUser, type BlockedUser } from '../lib/userApi';
import styles from './BlockedUsersSection.module.css';

export function BlockedUsersSection() {
  const [users, setUsers] = useState<BlockedUser[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchBlockedUsers()
      .then((u) => {
        if (!cancelled) setUsers(u);
      })
      .catch(() => {
        if (!cancelled) setError('Could not load your blocked users.');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const unblock = async (username: string) => {
    try {
      await unblockUser(username);
      setUsers((prev) => prev?.filter((u) => u.username !== username) ?? prev);
    } catch {
      setError('Could not unblock that user.');
    }
  };

  if (error) return <p className={styles.error}>{error}</p>;
  if (users === null) return <p className={styles.muted}>Loading…</p>;
  if (users.length === 0) return <p className={styles.muted}>You haven't blocked anyone.</p>;

  return (
    <ul className={styles.list}>
      {users.map((u) => (
        <li key={u.id} className={styles.row}>
          <Link to={`/user/${u.username}`}>u/{u.username}</Link>
          <button type="button" className={styles.unblock} onClick={() => unblock(u.username)}>
            Unblock
          </button>
        </li>
      ))}
    </ul>
  );
}
