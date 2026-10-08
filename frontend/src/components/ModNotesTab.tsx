import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../lib/apiClient';
import { addModNote, deleteModNote, fetchModNotes, type ModNote } from '../lib/moderationApi';
import { fetchPublicProfile } from '../lib/userApi';
import { timeAgo } from '../lib/time';
import styles from './ModNotesTab.module.css';

type Subject = { id: string; username: string };

// Private moderator-only notes about users in this community. Opening the tab shows the community's most recent notes (written by
// any moderator), loaded from the server every time, so nothing depends on what this browser tab remembers. Looking a user up filters
// the list to that user and lets you add a note about them. Never shown to the user themselves.
export function ModNotesTab({ communityName }: { communityName: string }) {
  const [username, setUsername] = useState('');
  const [subject, setSubject] = useState<Subject | null>(null); // null = the community-wide recent list
  const [notes, setNotes] = useState<ModNote[]>([]);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const load = useCallback(
    async (about: Subject | null) => {
      setLoading(true);
      try {
        setNotes(await fetchModNotes(communityName, about?.id));
        setError(null);
      } catch {
        setError('Could not load notes.');
      } finally {
        setLoading(false);
      }
    },
    [communityName],
  );

  useEffect(() => {
    load(null);
  }, [load]);

  const lookup = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const profile = await fetchPublicProfile(username.trim());
      const found = { id: profile.id, username: profile.username };
      setSubject(found);
      await load(found);
    } catch (err) {
      setError(err instanceof ApiError && err.status === 404 ? 'No such user.' : 'Could not load notes.');
    } finally {
      setBusy(false);
    }
  };

  const showAll = () => {
    setSubject(null);
    setUsername('');
    load(null);
  };

  const add = async (e: FormEvent) => {
    e.preventDefault();
    if (!subject || !draft.trim()) return;
    setError(null);
    try {
      await addModNote(communityName, subject.id, draft.trim());
      setDraft('');
      await load(subject); // re-read from the server rather than trusting local state
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not add the note.');
    }
  };

  const remove = async (id: string) => {
    setError(null);
    try {
      await deleteModNote(communityName, id);
      await load(subject);
    } catch (err) {
      setError(err instanceof ApiError && err.status === 403 ? "Only the note's author can delete it." : 'Could not delete the note.');
    }
  };

  return (
    <div>
      <form className={styles.form} onSubmit={lookup}>
        <input className={styles.input} placeholder="Username" value={username} onChange={(e) => setUsername(e.target.value)} />
        <button type="submit" className={styles.button} disabled={busy || !username.trim()}>
          Look up
        </button>
      </form>
      {error && <p className={styles.error}>{error}</p>}

      {subject ? (
        <>
          <h3 className={styles.heading}>
            Notes on u/{subject.username}{' '}
            <button type="button" className={styles.link} onClick={showAll}>
              Show all recent notes
            </button>
          </h3>
          <form className={styles.form} onSubmit={add}>
            <input
              className={styles.input}
              placeholder="Add a private note (only moderators can see it)"
              value={draft}
              maxLength={1000}
              onChange={(e) => setDraft(e.target.value)}
            />
            <button type="submit" className={styles.button} disabled={!draft.trim()}>
              Add note
            </button>
          </form>
        </>
      ) : (
        <>
          <h3 className={styles.heading}>Recent notes</h3>
          <p className={styles.muted}>Notes written by any moderator of this community. Look up a user above to see their notes or add a new one.</p>
        </>
      )}

      {loading ? (
        <p className={styles.muted}>Loading…</p>
      ) : notes.length === 0 ? (
        <p className={styles.muted}>{subject ? 'No notes yet.' : 'No notes in this community yet.'}</p>
      ) : (
        notes.map((n) => (
          <div key={n.id} className={styles.note}>
            {!subject && (
              <div className={styles.about}>
                About{' '}
                <button
                  type="button"
                  className={styles.link}
                  onClick={() => {
                    const found = { id: n.userId, username: n.subjectUsername ?? '[deleted]' };
                    setUsername(found.username);
                    setSubject(found);
                    load(found);
                  }}
                >
                  u/{n.subjectUsername ?? '[deleted]'}
                </button>
              </div>
            )}
            <div>{n.note}</div>
            <div className={styles.meta}>
              by u/{n.authorUsername ?? '[deleted]'} · {timeAgo(n.createdAt)}
              <button type="button" className={styles.delete} onClick={() => remove(n.id)}>
                Delete
              </button>
            </div>
          </div>
        ))
      )}
    </div>
  );
}
