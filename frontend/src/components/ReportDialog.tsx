import { useEffect, useState } from 'react';
import { ApiError } from '../lib/apiClient';
import { reportItem } from '../lib/engagementApi';
import type { TargetType } from '../types/engagement';
import { useToast } from './Toast/ToastContext';
import styles from './ReportDialog.module.css';

const REASONS = ['Spam', 'Harassment', 'Misinformation', 'Other'] as const;
const MAX_LEN = 500;

interface ReportDialogProps {
  targetType: TargetType;
  targetId: string;
  onClose: () => void;
}

export function ReportDialog({ targetType, targetId, onClose }: ReportDialogProps) {
  const toast = useToast();
  const [reason, setReason] = useState<(typeof REASONS)[number]>('Spam');
  const [details, setDetails] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  const text = details.trim();
  const needsDetails = reason === 'Other';
  const canSubmit = !submitting && (!needsDetails || text.length > 0);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    const full = (text ? `${reason}: ${text}` : reason).slice(0, MAX_LEN);
    try {
      await reportItem(targetType, targetId, full);
      toast.show('Report submitted. Thanks for letting us know.');
      onClose();
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 404 ? 'This content no longer exists.' : err instanceof ApiError ? err.message : 'Could not submit the report.',
      );
      setSubmitting(false);
    }
  };

  return (
    <div className={styles.overlay} onClick={onClose}>
      <form className={styles.dialog} role="dialog" aria-modal="true" aria-label={`Report ${targetType}`} onClick={(e) => e.stopPropagation()} onSubmit={submit}>
        <div className={styles.header}>
          <h2 className={styles.title}>Report this {targetType}</h2>
          <button type="button" className={styles.close} onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>
        <fieldset className={styles.reasons}>
          <legend className={styles.legend}>Why are you reporting it?</legend>
          {REASONS.map((r) => (
            <label key={r} className={styles.reason}>
              <input type="radio" name="report-reason" checked={reason === r} onChange={() => setReason(r)} /> {r}
            </label>
          ))}
        </fieldset>
        <textarea
          className={styles.details}
          placeholder={needsDetails ? 'Tell us what is wrong' : 'Add details (optional)'}
          maxLength={MAX_LEN - 20}
          value={details}
          onChange={(e) => setDetails(e.target.value)}
          rows={3}
        />
        {error && <p className={styles.error}>{error}</p>}
        <div className={styles.footer}>
          <button type="button" className={styles.cancel} onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className={styles.submit} disabled={!canSubmit}>
            {submitting ? 'Submitting…' : 'Submit'}
          </button>
        </div>
      </form>
    </div>
  );
}
