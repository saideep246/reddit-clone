import { useEffect, useId, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '../lib/apiClient';
import { deleteCommunity } from '../lib/communityApi';
import { useToast } from './Toast/ToastContext';
import styles from './DeleteCommunityDialog.module.css';

interface DeleteCommunityDialogProps {
  communityName: string;
  onClose: () => void;
}

// 404 means the community is already unavailable (deleted elsewhere), so there is nothing left to retry here.
function messageFor(err: unknown): { text: string; gone: boolean } {
  if (err instanceof ApiError) {
    if (err.status === 400) return { text: 'The name you typed does not match this community. Check it and try again.', gone: false };
    if (err.status === 401) return { text: 'Your session has expired. Please log in again to delete this community.', gone: false };
    if (err.status === 403) return { text: "Only the community's creator can delete it.", gone: false };
    if (err.status === 404) return { text: 'This community is already unavailable.', gone: true };
  }
  return { text: 'Could not delete the community. Please try again.', gone: false };
}

export function DeleteCommunityDialog({ communityName, onClose }: DeleteCommunityDialogProps) {
  const navigate = useNavigate();
  const toast = useToast();
  const titleId = useId();
  const descId = useId();
  const inputId = useId();
  const dialogRef = useRef<HTMLFormElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [typed, setTyped] = useState('');
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Exact, case-sensitive and untrimmed, like the backend's check. The backend still re-validates.
  const matches = typed === communityName;

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Escape closes (unless a delete is in flight); Tab is kept inside the dialog.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !deleting) {
        onClose();
        return;
      }
      if (e.key !== 'Tab' || !dialogRef.current) return;
      const focusable = dialogRef.current.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled])');
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose, deleting]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!matches || deleting) return;
    setDeleting(true);
    setError(null);
    try {
      await deleteCommunity(communityName, typed);
      toast.show(`r/${communityName} was deleted.`);
      onClose();
      navigate('/communities');
    } catch (err) {
      const { text, gone } = messageFor(err);
      if (gone) {
        toast.show(text);
        onClose();
        navigate('/communities');
        return;
      }
      setError(text);
      setDeleting(false);
    }
  };

  return (
    <div className={styles.overlay} onClick={() => !deleting && onClose()}>
      <form
        ref={dialogRef}
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descId}
        onClick={(e) => e.stopPropagation()}
        onSubmit={submit}
      >
        <h2 id={titleId} className={styles.title}>
          Delete r/{communityName}?
        </h2>
        <div id={descId} className={styles.body}>
          <p>The community will become unavailable to everyone. Its posts, comments and history are kept, and the name stays reserved.</p>
          <p>This cannot be undone from the site.</p>
        </div>
        <label className={styles.label} htmlFor={inputId}>
          Type <strong>{communityName}</strong> to confirm
        </label>
        <input
          id={inputId}
          ref={inputRef}
          className={styles.input}
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          autoComplete="off"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          disabled={deleting}
        />
        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}
        <div className={styles.footer}>
          <button type="button" className={styles.cancel} onClick={onClose} disabled={deleting}>
            Cancel
          </button>
          <button type="submit" className={styles.delete} disabled={!matches || deleting} aria-label={`Permanently delete community ${communityName}`}>
            {deleting ? 'Deleting…' : 'Delete community'}
          </button>
        </div>
      </form>
    </div>
  );
}
