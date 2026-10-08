import { useState } from 'react';
import { useFlairs } from '../hooks/useFlairs';
import type { Flair } from '../types/post';
import { DeleteFlairDialog } from './DeleteFlairDialog';
import { FlairChip } from './FlairChip';
import { FlairDialog } from './FlairDialog';
import styles from './FlairsSection.module.css';

interface FlairsSectionProps {
  communityName: string;
  // true only for a moderator holding "Manage flairs". Everyone who can open the Settings tab sees the list; only they see the
  // buttons. The backend enforces the same permission, so this is presentation, not authorization.
  canManageFlairs: boolean;
}

type DialogState = { kind: 'create'; type: 'post' | 'user' } | { kind: 'edit'; flair: Flair } | { kind: 'delete'; flair: Flair } | null;

export function FlairsSection({ communityName, canManageFlairs }: FlairsSectionProps) {
  const { postFlairs, userFlairs, loading, error, create, update, remove } = useFlairs(communityName);
  const [dialog, setDialog] = useState<DialogState>(null);

  const renderGroup = (title: string, type: 'post' | 'user', list: Flair[], note?: string) => (
    <div className={styles.group}>
      <h4 className={styles.groupTitle}>{title}</h4>
      {note && <p className={styles.note}>{note}</p>}
      {list.length === 0 ? (
        <p className={styles.empty}>No {type} flairs yet.</p>
      ) : (
        <ul className={styles.list}>
          {list.map((f) => (
            <li key={f.id} className={styles.row}>
              <FlairChip text={f.text} color={f.color} />
              {canManageFlairs && (
                <span className={styles.actions}>
                  <button type="button" className={styles.secondary} onClick={() => setDialog({ kind: 'edit', flair: f })} aria-label={`Edit flair ${f.text}`}>
                    Edit
                  </button>
                  <button type="button" className={styles.secondary} onClick={() => setDialog({ kind: 'delete', flair: f })} aria-label={`Delete flair ${f.text}`}>
                    Delete
                  </button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {canManageFlairs && (
        <button type="button" className={styles.create} onClick={() => setDialog({ kind: 'create', type })}>
          + Create {type} flair
        </button>
      )}
    </div>
  );

  return (
    <section className={styles.section} aria-labelledby="community-flairs">
      <h3 id="community-flairs" className={styles.title}>
        Flairs
      </h3>
      {!canManageFlairs && <p className={styles.note}>You can see this community's flairs. Only moderators with the "Manage flairs" permission can change them.</p>}
      {loading ? (
        <p className={styles.empty}>Loading…</p>
      ) : error ? (
        <p className={styles.error}>{error}</p>
      ) : (
        <>
          {renderGroup('Post flairs', 'post', postFlairs)}
          {renderGroup('User flairs', 'user', userFlairs, 'Members cannot choose or display user flairs in the app yet; this only manages the definitions.')}
        </>
      )}

      {dialog?.kind === 'create' && (
        <FlairDialog mode="create" type={dialog.type} onSubmit={(text, color) => create(dialog.type, text, color)} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'edit' && (
        <FlairDialog
          mode="edit"
          type={dialog.flair.type}
          initial={{ text: dialog.flair.text, color: dialog.flair.color }}
          onSubmit={(text, color) => update(dialog.flair.id, text, color)}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === 'delete' && <DeleteFlairDialog flair={dialog.flair} onConfirm={() => remove(dialog.flair.id)} onClose={() => setDialog(null)} />}
    </section>
  );
}
