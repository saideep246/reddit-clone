import { useEffect, useId, useRef, useState } from 'react';
import type { Flair } from '../types/post';
import { FlairChip } from './FlairChip';
import styles from './FlairDialog.module.css';

interface DeleteFlairDialogProps {
  flair: Flair;
  onConfirm: () => Promise<void>; // throws an Error with a user-readable message on failure
  onClose: () => void;
}

// Deleting a flair removes only the flair itself: the database clears it from posts and members that used it (ON DELETE SET NULL);
// nothing is deleted. The dialog says so, because "delete" next to existing content is otherwise alarming.
export function DeleteFlairDialog({ flair, onConfirm, onClose }: DeleteFlairDialogProps) {
  const titleId = useId();
  const descId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    cancelRef.current?.focus(); // the safe choice has focus, so Enter never deletes by accident
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !deleting) {
        onClose();
        return;
      }
      if (e.key !== 'Tab' || !dialogRef.current) return;
      const focusable = dialogRef.current.querySelectorAll<HTMLElement>('button:not([disabled])');
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

  const confirm = async () => {
    setDeleting(true);
    setError(null);
    try {
      await onConfirm();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not delete the flair.');
      setDeleting(false);
    }
  };

  return (
    <div className={styles.overlay} onClick={() => !deleting && onClose()}>
      <div
        ref={dialogRef}
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descId}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id={titleId} className={styles.title}>
          Delete this {flair.type} flair?
        </h2>
        <div id={descId} className={styles.body}>
          <p>
            <FlairChip text={flair.text} color={flair.color} />
          </p>
          <p>The flair will be removed. Posts and members that use it are kept and simply lose the flair. This cannot be undone.</p>
        </div>
        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}
        <div className={styles.footer}>
          <button ref={cancelRef} type="button" className={styles.cancel} onClick={onClose} disabled={deleting}>
            Cancel
          </button>
          <button type="button" className={styles.delete} onClick={confirm} disabled={deleting} aria-label={`Delete flair ${flair.text}`}>
            {deleting ? 'Deleting…' : 'Delete flair'}
          </button>
        </div>
      </div>
    </div>
  );
}
