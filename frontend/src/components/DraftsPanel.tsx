import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { deleteDraft, fetchDrafts, type Draft } from '../lib/postApi';
import { timeAgo } from '../lib/time';
import styles from './DraftsPanel.module.css';

// "Your drafts": server-side saved submit forms. `activeId` is the draft currently open (shown highlighted).
export function DraftsPanel({ activeId }: { activeId?: string | null }) {
  const [drafts, setDrafts] = useState<Draft[]>([]);

  useEffect(() => {
    let cancelled = false;
    fetchDrafts()
      .then((d) => {
        if (!cancelled) setDrafts(d);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [activeId]);

  if (drafts.length === 0) return null;

  return (
    <details className={styles.panel}>
      <summary className={styles.summary}>Your drafts ({drafts.length})</summary>
      {drafts.map((d) => (
        <div key={d.id} className={`${styles.row} ${d.id === activeId ? styles.active : ''}`}>
          <Link className={styles.link} to={d.communityName ? `/r/${d.communityName}/submit?draft=${d.id}` : `/submit?draft=${d.id}`}>
            {d.payload.title || '(untitled)'}
          </Link>
          <span className={styles.meta}>
            {d.communityName ? `r/${d.communityName} · ` : ''}
            {timeAgo(d.updatedAt)}
          </span>
          <button
            type="button"
            className={styles.delete}
            onClick={async () => {
              await deleteDraft(d.id).catch(() => {});
              setDrafts((prev) => prev.filter((x) => x.id !== d.id));
            }}
          >
            Delete
          </button>
        </div>
      ))}
    </details>
  );
}
