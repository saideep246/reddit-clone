import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { useModerators } from '../hooks/useModerators';
import type { UserSearchHit } from '../lib/userSearchApi';
import { UserPicker } from './UserPicker';
import { timeAgo } from '../lib/time';
import {
  ALL_PERMISSION_BITS,
  hasPermission,
  PERM_MANAGE_MODERATORS,
  PERMISSION_OPTIONS,
  PERMISSION_PRESETS,
  type ModeratorEntry,
  type ModeratorInviteEntry,
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

// "Pending invitations" holds only invitations someone can still act on. The server already leaves lapsed ones out of the list;
// this keeps a page that has been open past an expiry consistent with that, instead of showing a stale row.
function isLive(i: ModeratorInviteEntry): boolean {
  return i.status === 'pending' && new Date(i.expiresAt).getTime() > Date.now();
}

function expiryLabel(i: ModeratorInviteEntry): string {
  const days = Math.ceil((new Date(i.expiresAt).getTime() - Date.now()) / 86_400_000);
  return days <= 1 ? 'expires within a day' : `expires in ${days} days`;
}

export function ModeratorsTab({ communityName, myPermissions }: ModeratorsTabProps) {
  const { moderators, invites: fetchedInvites, loading, error, invite, cancelInvite, updatePermissions, remove } = useModerators(communityName);
  const mine = myPermissions ?? 0;

  // Re-evaluate liveness at the moment the soonest invitation lapses, so it leaves the list without a reload.
  const [, setNow] = useState(0);
  useEffect(() => {
    const next = fetchedInvites.filter(isLive).map((i) => new Date(i.expiresAt).getTime()).sort((a, b) => a - b)[0];
    if (next === undefined) return;
    const timer = setTimeout(() => setNow((n) => n + 1), Math.max(next - Date.now(), 0) + 250);
    return () => clearTimeout(timer);
  }, [fetchedInvites]);
  const invites = fetchedInvites.filter(isLive);
  const canManage = hasPermission(mine, PERM_MANAGE_MODERATORS);

  const [selected, setSelected] = useState<UserSearchHit | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);
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

  // People the picker should show greyed out: already moderators, or already holding a pending invitation.
  const unavailable = useMemo(() => {
    const map: Record<string, string> = {};
    invites.forEach((i) => {
      if (i.inviteeUsername) map[i.inviteeUsername.toLowerCase()] = 'Invitation pending';
    });
    moderators.forEach((m) => {
      if (m.username) map[m.username.toLowerCase()] = m.owner ? 'Owner' : 'Already a moderator';
    });
    return map;
  }, [invites, moderators]);

  const handleInvite = (e: FormEvent) => {
    e.preventDefault();
    if (!selected) return;
    if (newPerms === 0) {
      setFormError('Choose at least one permission.');
      return;
    }
    const name = selected.username;
    run(() => invite(name, newPerms), () => {
      setSentTo(name);
      setSelected(null);
    });
  };

  // Same rule as sending: you can only withdraw an invitation whose permissions fit inside your own. Everything listed here is
  // live and pending; the server is the final judge either way.
  const canCancel = (i: ModeratorInviteEntry) => canManage && (i.permissions & ~mine) === 0;

  // A moderator can only change someone whose permissions are a subset of their own (the backend enforces the same rule).
  const canChange = (m: ModeratorEntry) => canManage && !m.owner && (m.permissions & ~mine) === 0;

  return (
    <div>
      {canManage ? (
        <form className={styles.addCard} onSubmit={handleInvite}>
          <h3 className={styles.heading}>Invite a moderator</h3>
          {selected ? (
            <div className={styles.selected}>
              <span>
                Inviting <strong>u/{selected.username}</strong>
              </span>
              <button type="button" className={styles.secondary} onClick={() => setSelected(null)}>
                Change
              </button>
            </div>
          ) : (
            <UserPicker
              label="Find a user to invite"
              purpose="moderator"
              unavailable={unavailable}
              onSelect={(hit) => {
                setSelected(hit);
                setSentTo(null);
                setFormError(null);
              }}
            />
          )}
          {selected && (
            <>
              <PermissionPicker value={newPerms} onChange={setNewPerms} mine={mine} />
              <p className={styles.note}>They get a notification and become a moderator, and join the community, once they accept.</p>
              <button type="submit" className={styles.primary} disabled={busy}>
                {busy ? 'Sending…' : 'Send invitation'}
              </button>
            </>
          )}
        </form>
      ) : (
        <p className={styles.note}>You can see who moderates this community. Only moderators with the "Manage moderators" permission can change the list.</p>
      )}
      {sentTo && (
        <p className={styles.success} role="status">
          Moderator invitation sent to u/{sentTo}.
        </p>
      )}
      {formError && (
        <p className={styles.error} role="alert">
          {formError}
        </p>
      )}

      {!loading && !error && invites.length > 0 && (
        <section className={styles.invites} aria-labelledby="pending-invites-heading">
          <h3 id="pending-invites-heading" className={styles.heading}>
            Pending invitations
          </h3>
          {invites.map((i) => (
            <div key={i.id} className={styles.row}>
              <div className={styles.rowMain}>
                <div>
                  <strong>u/{i.inviteeUsername ?? '[deleted]'}</strong>
                  <span className={styles.statusPill}>Pending</span>
                  <span className={styles.meta}>
                    {' '}
                    · invited by u/{i.inviterUsername ?? '[deleted]'} {timeAgo(i.createdAt)} · {expiryLabel(i)}
                  </span>
                </div>
                <div className={styles.chips}>
                  <PermissionChips permissions={i.permissions} owner={false} />
                </div>
              </div>
              {canCancel(i) && (
                <div className={styles.actions}>
                  <button
                    type="button"
                    className={styles.secondary}
                    disabled={busy}
                    aria-label={`Cancel the invitation to u/${i.inviteeUsername ?? 'this user'}`}
                    onClick={() => run(() => cancelInvite(i.id), () => setSentTo(null))}
                  >
                    Cancel invitation
                  </button>
                </div>
              )}
            </div>
          ))}
        </section>
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
