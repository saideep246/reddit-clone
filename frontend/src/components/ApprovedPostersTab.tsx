import { useMemo, useState } from 'react';
import { useApprovedSubmitters } from '../hooks/useApprovedSubmitters';
import { timeAgo } from '../lib/time';
import type { UserSearchHit } from '../lib/userSearchApi';
import { UserPicker } from './UserPicker';
import styles from './ApprovedPostersTab.module.css';

interface ApprovedPostersTabProps {
  communityName: string;
}

// Restricted communities: anyone can read and join, but only moderators and the people listed here can post. This is where a
// moderator with "Manage access" adds and removes them. Finding a person here only picks them; the server still checks the
// moderator's permission when the approval is sent.
export function ApprovedPostersTab({ communityName }: ApprovedPostersTabProps) {
  const { submitters, loading, error, approve, remove } = useApprovedSubmitters(communityName);
  const [selected, setSelected] = useState<UserSearchHit | null>(null);
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);

  const unavailable = useMemo(() => {
    const map: Record<string, string> = {};
    submitters.forEach((s) => {
      if (s.username) map[s.username.toLowerCase()] = 'Already approved';
    });
    return map;
  }, [submitters]);

  const run = async (action: () => Promise<void>, success: string, after?: () => void) => {
    setBusy(true);
    setMessage(null);
    try {
      await action();
      setMessage({ kind: 'success', text: success });
      after?.();
    } catch (err) {
      setMessage({ kind: 'error', text: err instanceof Error ? err.message : 'Something went wrong.' });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <p className={styles.intro}>
        r/{communityName} is restricted: everyone can read it, but only moderators and the people below can post. Approve someone to let
        them post.
      </p>

      <div className={styles.addCard}>
        <h3 className={styles.heading}>Approve a poster</h3>
        {selected ? (
          <div className={styles.selected}>
            <span>
              Approve <strong>u/{selected.username}</strong> to post?
            </span>
            <div className={styles.actions}>
              <button
                type="button"
                className={styles.primary}
                disabled={busy}
                onClick={() => run(() => approve(selected.id), `u/${selected.username} can now post in r/${communityName}.`, () => setSelected(null))}
              >
                {busy ? 'Approving…' : 'Approve'}
              </button>
              <button type="button" className={styles.secondary} onClick={() => setSelected(null)}>
                Change
              </button>
            </div>
          </div>
        ) : (
          <UserPicker
            label="Find a user to approve"
            purpose="moderator"
            unavailable={unavailable}
            onSelect={(hit) => {
              setSelected(hit);
              setMessage(null);
            }}
          />
        )}
      </div>

      {message && (
        <p className={message.kind === 'success' ? styles.success : styles.error} role={message.kind === 'error' ? 'alert' : 'status'}>
          {message.text}
        </p>
      )}

      {loading ? (
        <div className={styles.state}>Loading…</div>
      ) : error ? (
        <div className={styles.state}>{error}</div>
      ) : submitters.length === 0 ? (
        <div className={styles.state}>No approved posters yet. Only moderators can post here right now.</div>
      ) : (
        submitters.map((s) => (
          <div key={s.userId} className={styles.row}>
            <div>
              <strong>u/{s.username ?? '[deleted]'}</strong>
              <div className={styles.meta}>
                approved by u/{s.approvedByUsername ?? '[deleted]'} {timeAgo(s.approvedAt)}
              </div>
            </div>
            <div className={styles.actions}>
              {confirmRemove === s.userId ? (
                <>
                  <button
                    type="button"
                    className={styles.danger}
                    disabled={busy}
                    aria-label={`Confirm removing u/${s.username ?? 'this user'} from approved posters`}
                    onClick={() => run(() => remove(s.userId), `u/${s.username ?? 'The user'} can no longer post.`, () => setConfirmRemove(null))}
                  >
                    Confirm remove
                  </button>
                  <button type="button" className={styles.secondary} onClick={() => setConfirmRemove(null)}>
                    Cancel
                  </button>
                </>
              ) : (
                <button type="button" className={styles.secondary} onClick={() => setConfirmRemove(s.userId)}>
                  Remove
                </button>
              )}
            </div>
          </div>
        ))
      )}
    </div>
  );
}
