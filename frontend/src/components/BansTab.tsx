import { useState, type FormEvent } from 'react';
import { useBans } from '../hooks/useBans';
import { timeAgo } from '../lib/time';
import styles from './BansTab.module.css';

interface BansTabProps {
  communityName: string;
}

export function BansTab({ communityName }: BansTabProps) {
  const { bans, loading, error, banByUsername, unban } = useBans(communityName);
  const [username, setUsername] = useState('');
  const [reason, setReason] = useState('');
  const [days, setDays] = useState(0); // 0 = permanent
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setFormError(null);
    if (!username.trim()) return;
    setSubmitting(true);
    try {
      const expiresAt = days > 0 ? new Date(Date.now() + days * 86_400_000).toISOString() : null;
      await banByUsername(username.trim(), reason.trim() || undefined, expiresAt);
      setUsername('');
      setReason('');
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Could not issue that ban.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div>
      <form className={styles.form} onSubmit={handleSubmit}>
        <input
          className={styles.input}
          placeholder="Username to ban"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
        <input
          className={styles.input}
          placeholder="Reason (optional)"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
        <select className={styles.input} value={days} onChange={(e) => setDays(Number(e.target.value))} aria-label="Ban duration">
          <option value={0}>Permanent</option>
          <option value={1}>1 day</option>
          <option value={3}>3 days</option>
          <option value={7}>7 days</option>
          <option value={30}>30 days</option>
        </select>
        <button type="submit" className={styles.submitButton} disabled={submitting || !username.trim()}>
          {submitting ? 'Banning…' : 'Issue ban'}
        </button>
      </form>
      {formError && <p className={styles.formError}>{formError}</p>}

      {loading ? (
        <div className={styles.state}>Loading…</div>
      ) : error ? (
        <div className={styles.state}>{error}</div>
      ) : bans.length === 0 ? (
        <div className={styles.state}>No one is banned from this community.</div>
      ) : (
        bans.map((b) => (
          <div key={b.userId} className={styles.row}>
            <div>
              <strong>u/{b.username ?? '[deleted]'}</strong>
              {b.reason && <span className={styles.reason}> — {b.reason}</span>}
              <div className={styles.meta}>
                banned by u/{b.issuerUsername ?? '[deleted]'} · {timeAgo(b.createdAt)}
                {b.expiresAt ? ` · expires ${new Date(b.expiresAt).toLocaleDateString()}` : ' · permanent'}
              </div>
            </div>
            <button type="button" className={styles.unbanButton} onClick={() => unban(b.userId)}>
              Unban
            </button>
          </div>
        ))
      )}
    </div>
  );
}
