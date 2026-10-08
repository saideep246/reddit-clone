import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { ApiError } from '../lib/apiClient';
import { searchUsersForPicker, USER_SEARCH_MIN_LENGTH, type UserSearchHit, type UserSearchPurpose } from '../lib/userSearchApi';
import styles from './UserPicker.module.css';

const DEBOUNCE_MS = 300;

interface UserPickerProps {
  label: string;
  purpose: UserSearchPurpose;
  onSelect: (user: UserSearchHit) => void;
  // Lower-cased username -> why it can't be picked ("Already a moderator"). Shown greyed out and not selectable.
  unavailable?: Record<string, string>;
  placeholder?: string;
  autoFocus?: boolean;
}

type Status = 'idle' | 'loading' | 'ready' | 'error';

// The one "find a person" control, used by the moderator-invite form and the chat starter. A combobox over a listbox:
// type (debounced, at least two characters), arrow keys to move, Enter to choose, Escape to close. It only finds people; what
// the choice is allowed to do is decided by the server when the form is submitted.
export function UserPicker({ label, purpose, onSelect, unavailable = {}, placeholder, autoFocus }: UserPickerProps) {
  const uid = useId();
  const listId = `${uid}-list`;
  const [text, setText] = useState('');
  const [results, setResults] = useState<UserSearchHit[]>([]);
  const [status, setStatus] = useState<Status>('idle');
  const [message, setMessage] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const requestId = useRef(0);
  const query = text.trim().replace(/^u\//i, '');
  const searchable = query.length >= USER_SEARCH_MIN_LENGTH;

  useEffect(() => {
    const id = ++requestId.current; // any older in-flight search is now stale and its answer is ignored
    if (!searchable) {
      return;
    }
    const timer = setTimeout(async () => {
      setStatus('loading');
      try {
        const hits = await searchUsersForPicker(query, purpose);
        if (id !== requestId.current) return;
        setResults(hits);
        setStatus('ready');
        setActive(hits.findIndex((h) => !unavailable[h.username.toLowerCase()]));
      } catch (err) {
        if (id !== requestId.current) return;
        setResults([]);
        setStatus('error');
        setMessage(err instanceof ApiError && err.status === 429 ? 'Too many searches. Wait a moment and try again.' : 'Could not search right now. Try again.');
      }
    }, DEBOUNCE_MS);
    return () => clearTimeout(timer);
    // `unavailable` only affects how rows look; it must not restart the search.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, purpose, searchable]);

  const choose = (hit: UserSearchHit) => {
    if (unavailable[hit.username.toLowerCase()]) return;
    onSelect(hit);
    setText('');
    setResults([]);
    setStatus('idle');
    setOpen(false);
  };

  const move = (delta: number) => {
    if (results.length === 0) return;
    let next = active;
    for (let i = 0; i < results.length; i++) {
      next = (next + delta + results.length) % results.length;
      if (!unavailable[results[next].username.toLowerCase()]) break;
    }
    setActive(next);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      move(1);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      move(-1);
    } else if (e.key === 'Enter' && open && active >= 0 && results[active]) {
      e.preventDefault(); // choosing a result must not submit the surrounding form
      choose(results[active]);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  const showPanel = open && searchable;
  const empty = status === 'ready' && results.length === 0;
  const activeId = active >= 0 && results[active] ? `${uid}-opt-${active}` : undefined;

  return (
    <div className={styles.wrap}>
      <label className={styles.label} htmlFor={`${uid}-input`}>
        {label}
      </label>
      <input
        id={`${uid}-input`}
        className={styles.input}
        type="text"
        role="combobox"
        autoComplete="off"
        autoFocus={autoFocus}
        aria-expanded={showPanel}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={showPanel ? activeId : undefined}
        placeholder={placeholder ?? 'Search by username'}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
          if (e.target.value.trim().replace(/^u\//i, '').length < USER_SEARCH_MIN_LENGTH) {
            setResults([]);
            setStatus('idle');
          } else {
            setStatus('loading');
          }
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 120)}
        onKeyDown={onKeyDown}
      />
      <div className={styles.hint} role="status" aria-live="polite">
        {text.trim() && !searchable ? `Type at least ${USER_SEARCH_MIN_LENGTH} characters to search.` : status === 'loading' ? 'Searching…' : ''}
      </div>
      {showPanel && (
        <ul id={listId} role="listbox" aria-label={`${label} results`} className={styles.list}>
          {status === 'loading' && results.length === 0 && <li className={styles.note}>Searching…</li>}
          {status === 'error' && (
            <li className={styles.error} role="alert">
              {message}
            </li>
          )}
          {empty && <li className={styles.note}>No users found for “{query}”.</li>}
          {results.map((hit, i) => {
            const reason = unavailable[hit.username.toLowerCase()];
            return (
              <li
                key={hit.id}
                id={`${uid}-opt-${i}`}
                role="option"
                aria-selected={i === active}
                aria-disabled={reason ? true : undefined}
                className={`${styles.option} ${i === active ? styles.active : ''} ${reason ? styles.disabled : ''}`}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => choose(hit)}
              >
                <span>u/{hit.username}</span>
                {reason && <span className={styles.reason}>{reason}</span>}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
