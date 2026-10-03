import { useCallback, useEffect, useState } from 'react';
import { fetchModLog, type ModLogEntry, type ModLogFilters } from '../lib/moderationApi';
import { fetchPublicProfile } from '../lib/userApi';
import { timeAgo } from '../lib/time';
import styles from './ModLogTab.module.css';

const ACTIONS = [
  'remove_post',
  'remove_comment',
  'ban',
  'unban',
  'unban_expired',
  'mute',
  'unmute',
  'sticky_comment',
  'unsticky_comment',
  'update_settings',
];

const PAGE = 100;

// The community's moderation audit log with filters (action, moderator, target type), paged backwards in time.
export function ModLogTab({ communityName }: { communityName: string }) {
  const [action, setAction] = useState('');
  const [targetType, setTargetType] = useState('');
  const [moderator, setModerator] = useState('');
  const [actorId, setActorId] = useState<string | undefined>(undefined);
  const [entries, setEntries] = useState<ModLogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (filters: ModLogFilters, append: boolean) => {
      setLoading(true);
      setError(null);
      try {
        const page = await fetchModLog(communityName, filters);
        setEntries((prev) => (append ? [...prev, ...page] : page));
        setHasMore(page.length >= PAGE);
      } catch {
        setError('Could not load the mod log.');
      } finally {
        setLoading(false);
      }
    },
    [communityName],
  );

  useEffect(() => {
    load({ action, targetType, actorId }, false);
  }, [load, action, targetType, actorId]);

  const applyModerator = async () => {
    const name = moderator.trim();
    if (!name) {
      setActorId(undefined);
      return;
    }
    try {
      setActorId((await fetchPublicProfile(name)).id);
      setError(null);
    } catch {
      setError('No such moderator.');
    }
  };

  return (
    <div>
      <div className={styles.filters}>
        <select className={styles.input} value={action} onChange={(e) => setAction(e.target.value)} aria-label="Action">
          <option value="">All actions</option>
          {ACTIONS.map((a) => (
            <option key={a} value={a}>
              {a.replace(/_/g, ' ')}
            </option>
          ))}
        </select>
        <select className={styles.input} value={targetType} onChange={(e) => setTargetType(e.target.value)} aria-label="Target type">
          <option value="">All targets</option>
          <option value="post">Posts</option>
          <option value="comment">Comments</option>
          <option value="user">Users</option>
          <option value="community">Community</option>
        </select>
        <input
          className={styles.input}
          placeholder="Moderator username"
          value={moderator}
          onChange={(e) => setModerator(e.target.value)}
          onBlur={applyModerator}
          onKeyDown={(e) => e.key === 'Enter' && applyModerator()}
        />
      </div>
      {error && <p className={styles.error}>{error}</p>}
      {loading && entries.length === 0 && <p className={styles.muted}>Loading…</p>}
      {!loading && entries.length === 0 && !error && <p className={styles.muted}>No matching actions.</p>}
      {entries.map((e) => (
        <div key={e.id} className={styles.row}>
          <span className={styles.action}>{e.action.replace(/_/g, ' ')}</span>
          <span className={styles.who}>
            by u/{e.actorUsername ?? '[deleted]'} · {e.targetType}
          </span>
          {e.reason && <span className={styles.reason}>“{e.reason}”</span>}
          <span className={styles.when}>{timeAgo(e.createdAt)}</span>
        </div>
      ))}
      {hasMore && (
        <button
          type="button"
          className={styles.more}
          disabled={loading}
          onClick={() => load({ action, targetType, actorId, before: entries[entries.length - 1]?.createdAt }, true)}
        >
          {loading ? 'Loading…' : 'Load older'}
        </button>
      )}
    </div>
  );
}
