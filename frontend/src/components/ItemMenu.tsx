import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useEngagement } from '../engagement/EngagementContext';
import type { TargetType } from '../types/engagement';
import { MoreIcon } from './icons';
import { ReportDialog } from './ReportDialog';
import styles from './PostActionBar.module.css';

interface ItemMenuProps {
  targetType: TargetType;
  targetId: string;
  authorId: string | null;
  // False for deleted/removed content: nothing left to save, hide or report.
  live: boolean;
  // Called after a successful hide (e.g. the thread page navigates away).
  onHidden?: () => void;
  // Compact variant for comment rows.
  compact?: boolean;
}

// Overflow menu with Save/Unsave, Hide and Report. Logged-out viewers get a login redirect on open.
export function ItemMenu({ targetType, targetId, authorId, live, onHidden, compact = false }: ItemMenuProps) {
  const { user } = useAuth();
  const navigate = useNavigate();
  const { isSaved, toggleSave, hide } = useEngagement();
  const [open, setOpen] = useState(false);
  const [reporting, setReporting] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (!live) return null;
  const own = !!user && user.id === authorId;
  const saved = isSaved(targetType, targetId);

  const toggle = () => {
    if (!user) {
      navigate('/login');
      return;
    }
    setOpen((o) => !o);
  };

  return (
    <div className={styles.menuWrap} ref={ref} onClick={(e) => e.stopPropagation()}>
      <button
        ref={triggerRef}
        type="button"
        className={styles.pill}
        style={compact ? { height: 24, padding: '0 var(--space-2)' } : undefined}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="More actions"
        onClick={toggle}
      >
        <MoreIcon />
      </button>
      {open && (
        <div className={styles.menu} role="menu">
          <button
            type="button"
            role="menuitem"
            className={styles.menuItem}
            onClick={() => {
              setOpen(false);
              void toggleSave(targetType, targetId);
            }}
          >
            {saved ? 'Unsave' : 'Save'}
          </button>
          <button
            type="button"
            role="menuitem"
            className={styles.menuItem}
            onClick={async () => {
              setOpen(false);
              if (await hide(targetType, targetId)) onHidden?.();
            }}
          >
            Hide
          </button>
          {!own && (
            <button
              type="button"
              role="menuitem"
              className={styles.menuItem}
              onClick={() => {
                setOpen(false);
                setReporting(true);
              }}
            >
              Report
            </button>
          )}
        </div>
      )}
      {reporting && <ReportDialog targetType={targetType} targetId={targetId} onClose={() => setReporting(false)} />}
    </div>
  );
}
