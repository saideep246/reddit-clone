import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../lib/apiClient';
import { cancelPostingRequest, requestPostingApproval } from '../lib/communityApi';
import styles from './PostingRestricted.module.css';

// While a request is waiting, look again this often so an approval shows up (as the Create Post flow) without a manual reload.
const POLL_MS = 30_000;

interface PostingRestrictedProps {
  communityName: string;
  // Whether this person already has a request waiting for a moderator (from the community's postingRequestPending).
  pending: boolean;
  // Called after the request changes, and periodically while pending, so the parent re-reads the community.
  onChanged: () => void | Promise<void>;
  // On the composer page there is a way back to the community; on the community page itself there is not.
  showBackLink?: boolean;
}

// Shown in place of "Create Post" to someone who may not post in a restricted community: why, and how to ask. Asking is a request
// to the moderators for posting access. It is not joining (membership is separate) and grants nothing until a moderator approves.
// The server still refuses posts from anyone not approved, so this only saves them from a form that could never be sent.
export function PostingRestricted({ communityName, pending, onChanged, showBackLink = false }: PostingRestrictedProps) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!pending) return;
    const timer = setInterval(() => {
      onChanged();
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [pending, onChanged]);

  const run = async (action: () => Promise<unknown>, success: string) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      setNotice(success);
      await onChanged();
    } catch (err) {
      setNotice(null);
      setError(err instanceof ApiError && err.message ? err.message : 'Something went wrong. Please try again.');
      await onChanged(); // the server may know better (for example, you were approved in the meantime)
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={styles.box} role="note">
      <strong className={styles.title}>Posting is restricted</strong>
      <p className={styles.text}>Only approved submitters and moderators can post in this community.</p>

      {pending ? (
        <>
          {notice && (
            <p className={styles.notice} role="status">
              {notice}
            </p>
          )}
          <div className={styles.actions}>
            <button type="button" className={styles.pendingButton} disabled>
              Request pending
            </button>
            <button
              type="button"
              className={styles.secondary}
              disabled={busy}
              onClick={() => run(() => cancelPostingRequest(communityName), 'Request cancelled.')}
            >
              Cancel request
            </button>
          </div>
        </>
      ) : (
        <div className={styles.actions}>
          <button
            type="button"
            className={styles.primary}
            disabled={busy}
            onClick={() => run(() => requestPostingApproval(communityName), 'Posting approval requested. A moderator will review your request.')}
          >
            {busy ? 'Sending…' : 'Request posting approval'}
          </button>
        </div>
      )}

      {!pending && notice && (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      )}
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      {showBackLink && (
        <Link to={`/r/${communityName}`} className={styles.link}>
          Back to r/{communityName}
        </Link>
      )}
    </div>
  );
}
