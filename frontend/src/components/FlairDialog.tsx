import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { FlairChip } from './FlairChip';
import styles from './FlairDialog.module.css';

const MAX_TEXT = 64; // flairs.text is VARCHAR(64)
const DEFAULT_COLOR = '#0079d3';

interface FlairDialogProps {
  mode: 'create' | 'edit';
  type: 'post' | 'user'; // fixed: a flair's type never changes after it is created
  initial?: { text: string; color: string };
  onSubmit: (text: string, color: string) => Promise<void>; // throws an Error with a user-readable message on failure
  onClose: () => void;
}

// Create / edit dialog: name, colour picker and a live preview of the chip. Frontend checks are a convenience; the backend validates
// again (and is the only place duplicates are detected).
export function FlairDialog({ mode, type, initial, onSubmit, onClose }: FlairDialogProps) {
  const titleId = useId();
  const textId = useId();
  const colorId = useId();
  const dialogRef = useRef<HTMLFormElement>(null);
  const textRef = useRef<HTMLInputElement>(null);
  const [text, setText] = useState(initial?.text ?? '');
  const [color, setColor] = useState(initial?.color ?? DEFAULT_COLOR);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const trimmed = text.trim();
  const valid = trimmed.length > 0 && trimmed.length <= MAX_TEXT;

  useEffect(() => {
    textRef.current?.focus();
    textRef.current?.select();
  }, []);

  // Escape closes (unless a save is in flight); Tab stays inside the dialog.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !saving) {
        onClose();
        return;
      }
      if (e.key !== 'Tab' || !dialogRef.current) return;
      const focusable = dialogRef.current.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled])');
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose, saving]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!valid || saving) return;
    setSaving(true);
    setError(null);
    try {
      await onSubmit(trimmed, color);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save the flair.');
      setSaving(false);
    }
  };

  const title = `${mode === 'create' ? 'Create' : 'Edit'} ${type} flair`;

  return (
    <div className={styles.overlay} onClick={() => !saving && onClose()}>
      <form
        ref={dialogRef}
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(e) => e.stopPropagation()}
        onSubmit={submit}
      >
        <h2 id={titleId} className={styles.title}>
          {title.charAt(0).toUpperCase() + title.slice(1)}
        </h2>
        <label className={styles.label} htmlFor={textId}>
          Name <span className={styles.count}>{trimmed.length}/{MAX_TEXT}</span>
        </label>
        <input
          id={textId}
          ref={textRef}
          className={styles.input}
          value={text}
          maxLength={MAX_TEXT + 20}
          onChange={(e) => setText(e.target.value)}
          disabled={saving}
          autoComplete="off"
        />
        <label className={styles.label} htmlFor={colorId}>
          Colour
        </label>
        <div className={styles.colorRow}>
          <input id={colorId} className={styles.colorInput} type="color" value={color} onChange={(e) => setColor(e.target.value)} disabled={saving} />
          <span className={styles.hex}>{color}</span>
        </div>
        <div className={styles.preview} aria-live="polite">
          Preview: <FlairChip text={trimmed} color={color} />
        </div>
        {trimmed.length > MAX_TEXT && (
          <p className={styles.error} role="alert">
            The name can be at most {MAX_TEXT} characters.
          </p>
        )}
        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}
        <div className={styles.footer}>
          <button type="button" className={styles.cancel} onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="submit" className={styles.submit} disabled={!valid || saving}>
            {saving ? 'Saving…' : mode === 'create' ? 'Create flair' : 'Save changes'}
          </button>
        </div>
      </form>
    </div>
  );
}
