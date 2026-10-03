import { useState, type FormEvent } from 'react';
import { ApiError } from '../lib/apiClient';
import { addModNote, deleteModNote, fetchModNotes, type ModNote } from '../lib/moderationApi';
import { fetchPublicProfile } from '../lib/userApi';
import { timeAgo } from '../lib/time';
import styles from './ModNotesTab.module.css';

// Private moderator-only notes about a user in this community: look a user up, read what the team has written,
// add context ("warned for spam on Tuesday"). Never shown to the user themselves.
export function ModNotesTab({ communityName }: { communityName: string }) {
  const [username, setUsername] = useState('');
  const [subject, setSubject] = useState<{ id: string; username: string } | null>(null);
  const [notes, setNotes] = useState<ModNote[]>([]);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const lookup = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const profile = await fetchPublicProfile(username.trim());
      setSubject({ id: profile.id, username: profile.username });
      setNotes(await fetchModNotes(communityName, profile.id));
    } catch (err) {
      setSubject(null);
      setError(err instanceof ApiError && err.status === 404 ? 'No such user.' : 'Could not load notes.');
    } finally {
      setBusy(false);
    }
  };

  const add = async (e: FormEvent) => {
    e.preventDefault();
    if (!subject || !draft.trim()) return;
    setError(null);
    try {
      const created = await addModNote(communityName, subject.id, draft.trim());
      setNotes((prev) => [created, ...prev]);
      setDraft('');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not add the note.');
    }
  };

  const remove = async (id: string) => {
    setError(null);
    try {
      await deleteModNote(communityName, id);
      setNotes((prev) => prev.filter((n) => n.id !== id));
    } catch (err) {
      setError(err instanceof ApiError && err.status === 403 ? 'Only the note\'s author can delete it.' : 'Could not delete the note.');
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

      {subject && (
        <>
          <h3 className={styles.heading}>Notes on u/{subject.username}</h3>
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
          {notes.length === 0 && <p className={styles.muted}>No notes yet.</p>}
          {notes.map((n) => (
            <div key={n.id} className={styles.note}>
              <div>{n.note}</div>
              <div className={styles.meta}>
                u/{n.authorUsername ?? '[deleted]'} · {timeAgo(n.createdAt)}
                <button type="button" className={styles.delete} onClick={() => remove(n.id)}>
                  Delete
                </button>
              </div>
            </div>
          ))}
        </>
      )}
    </div>
  );
}
