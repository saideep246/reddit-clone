import { useState, type FormEvent } from 'react';
import { useModerators } from '../hooks/useModerators';
import { timeAgo } from '../lib/time';
import {
  ALL_PERMISSION_BITS,
  hasPermission,
  PERM_MANAGE_MODERATORS,
  PERMISSION_OPTIONS,
  PERMISSION_PRESETS,
  type ModeratorEntry,
} from '../types/moderation';
import styles from './ModeratorsTab.module.css';

interface ModeratorsTabProps {
  communityName: string;
  myPermissions: number | null | undefined;
}

// Checkboxes for the individual permission bits. A bit the signed-in moderator does not hold themselves is shown disabled: the
// backend refuses to grant beyond your own permissions, so offering it would only end in an error.
function PermissionPicker({ value, onChange, mine }: { value: number; onChange: (next: number) => void; mine: number }) {
  return (
    <div className={styles.picker}>
      <div className={styles.presets}>
        {PERMISSION_PRESETS.map((p) => (
          <button key={p.label} type="button" className={styles.preset} onClick={() => onChange(p.bits & mine)}>
            {p.label}
          </button>
        ))}
      </div>
      <div className={styles.checks}>
        {PERMISSION_OPTIONS.map((o) => {
          const allowed = hasPermission(mine, o.bit);
          return (
            <label key={o.bit} className={`${styles.check} ${allowed ? '' : styles.checkDisabled}`} title={o.description}>
              <input
                type="checkbox"
                checked={(value & o.bit) === o.bit}
                disabled={!allowed}
                onChange={(e) => onChange(e.target.checked ? value | o.bit : value & ~o.bit)}
              />
              {o.label}
            </label>
          );
        })}
      </div>
    </div>
  );
}

function PermissionChips({ permissions, owner }: { permissions: number; owner: boolean }) {
  if (owner || (permissions & ALL_PERMISSION_BITS) === ALL_PERMISSION_BITS) {
    return <span className={styles.chip}>Full permissions</span>;
  }
  const held = PERMISSION_OPTIONS.filter((o) => hasPermission(permissions, o.bit));
  if (held.length === 0) return <span className={styles.chipMuted}>No permissions</span>;
  return (
    <>
      {held.map((o) => (
        <span key={o.bit} className={styles.chip}>
          {o.label}
        </span>
      ))}
    </>
  );
}

export function ModeratorsTab({ communityName, myPermissions }: ModeratorsTabProps) {
  const { moderators, loading, error, addByUsername, updatePermissions, remove } = useModerators(communityName);
  const mine = myPermissions ?? 0;
  const canManage = hasPermission(mine, PERM_MANAGE_MODERATORS);

  const [username, setUsername] = useState('');
  const [newPerms, setNewPerms] = useState(PERMISSION_PRESETS[0].bits & mine);
  const [editing, setEditing] = useState<string | null>(null);
  const [editPerms, setEditPerms] = useState(0);
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const run = async (action: () => Promise<void>, after?: () => void) => {
    setBusy(true);
    setFormError(null);
    try {
      await action();
      after?.();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  };

  const handleAdd = (e: FormEvent) => {
    e.preventDefault();
    const name = username.trim().replace(/^u\//i, '');
    if (!name) return;
    if (newPerms === 0) {
      setFormError('Choose at least one permission.');
      return;
    }
    run(() => addByUsername(name, newPerms), () => setUsername(''));
  };

  // A moderator can only change someone whose permissions are a subset of their own (the backend enforces the same rule).
  const canChange = (m: ModeratorEntry) => canManage && !m.owner && (m.permissions & ~mine) === 0;

  return (
    <div>
      {canManage ? (
        <form className={styles.addCard} onSubmit={handleAdd}>
          <h3 className={styles.heading}>Add a moderator</h3>
          <input
            className={styles.input}
            placeholder="Username (they do not need to be a member)"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            aria-label="Username to make a moderator"
          />
          <PermissionPicker value={newPerms} onChange={setNewPerms} mine={mine} />
          <button type="submit" className={styles.primary} disabled={busy || !username.trim()}>
            {busy ? 'Adding…' : 'Add moderator'}
          </button>
        </form>
      ) : (
        <p className={styles.note}>You can see who moderates this community. Only moderators with the "Manage moderators" permission can change the list.</p>
      )}
      {formError && (
        <p className={styles.error} role="alert">
          {formError}
        </p>
      )}

      {loading ? (
        <div className={styles.state}>Loading…</div>
      ) : error ? (
        <div className={styles.state}>{error}</div>
      ) : (
        moderators.map((m) => (
          <div key={m.userId} className={styles.row}>
            <div className={styles.rowMain}>
              <div>
                <strong>u/{m.username ?? '[deleted]'}</strong>
                {m.owner && <span className={styles.ownerBadge}>Owner</span>}
                <span className={styles.meta}> · added {timeAgo(m.addedAt)}</span>
              </div>
              <div className={styles.chips}>
                <PermissionChips permissions={m.permissions} owner={m.owner} />
              </div>
              {editing === m.userId && (
                <div className={styles.editBox}>
                  <PermissionPicker value={editPerms} onChange={setEditPerms} mine={mine} />
                  <div className={styles.actions}>
                    <button
                      type="button"
                      className={styles.primary}
                      disabled={busy || editPerms === 0}
                      onClick={() => run(() => updatePermissions(m.userId, editPerms), () => setEditing(null))}
                    >
                      Save permissions
                    </button>
                    <button type="button" className={styles.secondary} onClick={() => setEditing(null)}>
                      Cancel
                    </button>
                  </div>
                </div>
              )}
            </div>
            {canChange(m) && editing !== m.userId && (
              <div className={styles.actions}>
                <button
                  type="button"
                  className={styles.secondary}
                  onClick={() => {
                    setEditing(m.userId);
                    setEditPerms(m.permissions & ALL_PERMISSION_BITS);
                    setConfirmRemove(null);
                    setFormError(null);
                  }}
                >
                  Edit
                </button>
                {confirmRemove === m.userId ? (
                  <>
                    <button
                      type="button"
                      className={styles.danger}
                      disabled={busy}
                      onClick={() => run(() => remove(m.userId), () => setConfirmRemove(null))}
                      aria-label={`Confirm removing u/${m.username ?? 'this user'} as moderator`}
                    >
                      Confirm remove
                    </button>
                    <button type="button" className={styles.secondary} onClick={() => setConfirmRemove(null)}>
                      Cancel
                    </button>
                  </>
                ) : (
                  <button type="button" className={styles.secondary} onClick={() => setConfirmRemove(m.userId)}>
                    Remove
                  </button>
                )}
              </div>
            )}
          </div>
        ))
      )}
    </div>
  );
}
