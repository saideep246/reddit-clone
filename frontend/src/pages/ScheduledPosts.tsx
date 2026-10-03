import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../lib/apiClient';
import { cancelScheduledPost, fetchScheduledPosts, type ScheduledPost } from '../lib/postApi';
import styles from './ScheduledPosts.module.css';

const WHEN = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

export function ScheduledPosts() {
  const { user } = useAuth();
  const [items, setItems] = useState<ScheduledPost[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    fetchScheduledPosts()
      .then((r) => {
        if (!cancelled) setItems(r);
      })
      .catch(() => {
        if (!cancelled) setError('Could not load your scheduled posts.');
      });
    return () => {
      cancelled = true;
    };
  }, [user]);

  if (!user) {
    return (
      <div className={styles.page}>
        <Link to="/login">Log in</Link> to see your scheduled posts.
      </div>
    );
  }

  const cancel = async (id: string) => {
    try {
      await cancelScheduledPost(id);
      setItems((prev) => prev?.map((i) => (i.id === id ? { ...i, status: 'cancelled' } : i)) ?? prev);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not cancel that post.');
    }
  };

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Scheduled posts</h1>
      {error && <p className={styles.error}>{error}</p>}
      {items === null && !error && <p className={styles.muted}>Loading…</p>}
      {items?.length === 0 && (
        <p className={styles.muted}>
          Nothing scheduled. Use "Schedule for later" when <Link to="/submit">creating a post</Link>.
        </p>
      )}
      {items?.map((i) => (
        <div key={i.id} className={styles.row}>
          <div className={styles.main}>
            <div className={styles.rowTitle}>{i.title || '(untitled)'}</div>
            <div className={styles.meta}>
              r/{i.communityName} · {i.kind} · {WHEN.format(new Date(i.publishAt))}
            </div>
            {i.status === 'failed' && <div className={styles.error}>Failed: {i.error}</div>}
          </div>
          <span className={`${styles.status} ${styles[i.status]}`}>{i.status}</span>
          {i.status === 'pending' && (
            <button type="button" className={styles.cancel} onClick={() => cancel(i.id)}>
              Cancel
            </button>
          )}
          {i.status === 'published' && i.postId && <Link to={`/r/${i.communityName}/comments/${i.postId}`}>View</Link>}
        </div>
      ))}
    </div>
  );
}
