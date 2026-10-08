import { useState } from 'react';
import { Link } from 'react-router-dom';
import { acceptModeratorInvite, declineModeratorInvite, inviteErrorMessage } from '../lib/moderatorInviteApi';
import { timeAgo } from '../lib/time';
import { useNotifications } from '../notifications/NotificationsContext';
import { hasPermission, PERMISSION_OPTIONS, type MyModeratorInvite } from '../types/moderation';
import styles from './PendingModeratorInvites.module.css';

function daysLeft(expiresAt: string): string {
  const ms = new Date(expiresAt).getTime() - Date.now();
  if (ms <= 0) return 'expired';
  const days = Math.ceil(ms / 86_400_000);
  return days === 1 ? 'expires in 1 day' : `expires in ${days} days`;
}

function permissionLabels(bits: number): string[] {
  return PERMISSION_OPTIONS.filter((o) => hasPermission(bits, o.bit)).map((o) => o.label);
}

interface Result {
  kind: 'success' | 'error';
  text: string;
  communityName?: string;
}

// Always shown on the notifications page when there is anything pending, whatever the user's notification mute settings are:
// it reads GET /api/moderator-invites directly, not the notification rows.
export function PendingModeratorInvites() {
  const { pendingInvites, refresh, refreshInvites, notifications, markRead } = useNotifications();
  const [busyId, setBusyId] = useState<string | null>(null);
  const [results, setResults] = useState<Record<string, Result>>({});

  const respond = async (invite: MyModeratorInvite, action: 'accept' | 'decline') => {
    setBusyId(invite.id);
    try {
      if (action === 'accept') await acceptModeratorInvite(invite.id);
      else await declineModeratorInvite(invite.id);
      setResults((r) => ({
        ...r,
        [invite.id]:
          action === 'accept'
            ? { kind: 'success', text: `You are now a moderator of r/${invite.communityName}.`, communityName: invite.communityName }
            : { kind: 'success', text: `You declined the invitation to moderate r/${invite.communityName}.` },
      }));
      // The matching inbox row, if one exists, has been dealt with.
      notifications.filter((n) => n.type === 'mod_invite' && n.source.inviteId === invite.id && !n.readAt).forEach((n) => markRead(n.id).catch(() => undefined));
    } catch (err) {
      setResults((r) => ({ ...r, [invite.id]: { kind: 'error', text: inviteErrorMessage(err) } }));
    } finally {
      setBusyId(null);
      // Server truth either way: an answered, expired or withdrawn invitation drops off the list.
      await Promise.all([refreshInvites(), refresh()]);
    }
  };

  const answered = Object.entries(results).filter(([id]) => !pendingInvites.some((i) => i.id === id));
  if (pendingInvites.length === 0 && answered.length === 0) return null;

  return (
    <section className={styles.panel} aria-labelledby="pending-invites-title">
      <h2 id="pending-invites-title" className={styles.title}>
        Moderator invitations
        {pendingInvites.length > 0 && <span className={styles.count}>{pendingInvites.length}</span>}
      </h2>

      {answered.map(([id, result]) => (
        <div key={id} className={result.kind === 'success' ? styles.success : styles.error} role={result.kind === 'error' ? 'alert' : 'status'}>
          {result.text}
          {result.communityName && (
            <>
              {' '}
              <Link to={`/r/${result.communityName}/mod`}>Open mod tools</Link>
            </>
          )}
        </div>
      ))}

      <ul className={styles.list}>
        {pendingInvites.map((invite) => {
          const labels = permissionLabels(invite.permissions);
          const result = results[invite.id];
          return (
            <li key={invite.id} className={styles.item}>
              <p className={styles.text}>
                <strong>u/{invite.inviterUsername ?? '[deleted]'}</strong> invited you to become a moderator of{' '}
                <Link to={`/r/${invite.communityName}`}>r/{invite.communityName}</Link>.
              </p>
              <p className={styles.meta}>
                {labels.length > 0 ? `Permissions: ${labels.join(', ')}` : 'No special permissions'} · sent {timeAgo(invite.createdAt)} · {daysLeft(invite.expiresAt)}
              </p>
              {result?.kind === 'error' && (
                <div className={styles.error} role="alert">
                  {result.text}
                </div>
              )}
              <div className={styles.actions}>
                <button type="button" className={styles.accept} disabled={busyId === invite.id} onClick={() => respond(invite, 'accept')}>
                  Accept
                </button>
                <button type="button" className={styles.decline} disabled={busyId === invite.id} onClick={() => respond(invite, 'decline')}>
                  Decline
                </button>
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
