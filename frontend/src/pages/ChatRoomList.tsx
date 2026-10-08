import { useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useChat } from '../chat/ChatContext';
import { UserPicker } from '../components/UserPicker';
import { ApiError } from '../lib/apiClient';
import { decodeHtmlEntities } from '../lib/html';
import { timeAgo } from '../lib/time';
import styles from './ChatRoomList.module.css';

export function ChatRoomList() {
  const { user } = useAuth();
  const { rooms, loadingRooms, startRoom } = useChat();
  const navigate = useNavigate();
  // The people picked for the new chat, in the order chosen. One name starts a direct chat; several start a group.
  const [picked, setPicked] = useState<string[]>([]);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const unavailable = useMemo(() => Object.fromEntries(picked.map((n) => [n.toLowerCase(), 'Already added'])), [picked]);

  const handleStart = async (e: FormEvent) => {
    e.preventDefault();
    const usernames = picked;
    if (usernames.length === 0) return;
    setStarting(true);
    setError(null);
    try {
      const roomId = await startRoom(usernames);
      setPicked([]);
      navigate(`/chat/${roomId}`);
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        setError('No such user — check the username(s) and try again.');
      } else if (err instanceof ApiError && err.status === 400) {
        setError('A chat needs at least one other person.');
      } else if (err instanceof ApiError && err.status === 403) {
        // The server's own rule (blocked, or this person only accepts messages from people they know) is the final word.
        setError(err.message || 'You can\'t start a chat with this person.');
      } else {
        setError('Could not start that chat.');
      }
    } finally {
      setStarting(false);
    }
  };

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Chat</h1>

      <form className={styles.startForm} onSubmit={handleStart}>
        <div className={styles.startPicker}>
          <UserPicker
            label="Start a chat with"
            purpose="chat"
            unavailable={unavailable}
            placeholder="Search by username"
            onSelect={(hit) => setPicked((prev) => (prev.some((n) => n.toLowerCase() === hit.username.toLowerCase()) ? prev : [...prev, hit.username]))}
          />
          {picked.length > 0 && (
            <ul className={styles.chosen} aria-label="People in this chat">
              {picked.map((name) => (
                <li key={name} className={styles.chip}>
                  u/{name}
                  <button type="button" className={styles.chipRemove} aria-label={`Remove u/${name}`} onClick={() => setPicked((prev) => prev.filter((n) => n !== name))}>
                    ×
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        <button type="submit" className={styles.startButton} disabled={starting || picked.length === 0}>
          Start
        </button>
      </form>
      {error && <div className={styles.error}>{error}</div>}

      {loadingRooms && rooms.length === 0 ? (
        <div className={styles.state}>Loading…</div>
      ) : rooms.length === 0 ? (
        <div className={styles.state}>No conversations yet.</div>
      ) : (
        <div className={styles.list}>
          {rooms.map((r) => {
            const lastMessage = decodeHtmlEntities(r.lastMessageBody);
            const preview =
              lastMessage === null
                ? ''
                : r.lastMessageSenderId === user?.id
                  ? `You: ${lastMessage}`
                  : lastMessage;
            return (
              <Link key={r.roomId} to={`/chat/${r.roomId}`} className={`${styles.row} ${r.unreadCount > 0 ? styles.unread : ''}`}>
                <div className={styles.rowMain}>
                  <span className={styles.participants}>{r.otherParticipants.join(', ') || '(empty room)'}</span>
                  {preview && <p className={styles.preview}>{preview}</p>}
                </div>
                <div className={styles.rowMeta}>
                  {r.lastMessageAt && <span className={styles.time}>{timeAgo(r.lastMessageAt)}</span>}
                  {r.unreadCount > 0 && <span className={styles.badge}>{r.unreadCount}</span>}
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
