import { useEffect, useState } from 'react';
import { ApiError } from '../lib/apiClient';
import { decodeHtmlEntities } from '../lib/html';
import { timeAgo } from '../lib/time';
import styles from './EditHistoryDialog.module.css';

export interface HistoryRevision {
  id: string;
  editedAt: string;
  title?: string | null;
  body?: string | null;
  url?: string | null;
}

interface EditHistoryDialogProps {
  load: () => Promise<HistoryRevision[]>;
  onClose: () => void;
}

// Shows prior revisions (newest first). Each row is the content as it was BEFORE the edit made at that time.
export function EditHistoryDialog({ load, onClose }: EditHistoryDialogProps) {
  const [revisions, setRevisions] = useState<HistoryRevision[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    load()
      .then((r) => {
        if (!cancelled) setRevisions(r);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof ApiError && e.status === 403 ? 'Only the author and moderators can see edit history.' : 'Could not load edit history.');
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.dialog} role="dialog" aria-modal="true" aria-label="Edit history" onClick={(e) => e.stopPropagation()}>
        <div className={styles.header}>
          <h2 className={styles.title}>Edit history</h2>
          <button type="button" className={styles.close} onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>
        {error && <p className={styles.error}>{error}</p>}
        {!error && revisions === null && <p>Loading…</p>}
        {revisions?.length === 0 && <p>No earlier versions.</p>}
        {revisions?.map((r) => (
          <div key={r.id} className={styles.revision}>
            <div className={styles.when}>Before the edit {timeAgo(r.editedAt)}</div>
            {r.title && <div className={styles.revTitle}>{decodeHtmlEntities(r.title)}</div>}
            {r.url && <div className={styles.revUrl}>{r.url}</div>}
            {r.body && <div className={styles.revBody}>{decodeHtmlEntities(r.body)}</div>}
          </div>
        ))}
      </div>
    </div>
  );
}
