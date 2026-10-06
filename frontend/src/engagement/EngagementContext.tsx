import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { useToast } from '../components/Toast/ToastContext';
import { ApiError } from '../lib/apiClient';
import { hideItem, saveItem, unhideItem, unsaveItem } from '../lib/engagementApi';
import type { TargetType } from '../types/engagement';

interface EngagementContextValue {
  isSaved: (type: TargetType, id: string) => boolean;
  isHidden: (type: TargetType, id: string) => boolean;
  toggleSave: (type: TargetType, id: string) => Promise<void>;
  hide: (type: TargetType, id: string) => Promise<boolean>;
  markSaved: (type: TargetType, ids: string[]) => void;
}

const EngagementContext = createContext<EngagementContextValue | null>(null);

const key = (type: TargetType, id: string) => `${type}:${id}`;

const errMessage = (e: unknown, fallback: string) => (e instanceof ApiError && e.message ? e.message : fallback);

function withKey(set: Set<string>, k: string, present: boolean): Set<string> {
  const next = new Set(set);
  if (present) next.add(k);
  else next.delete(k);
  return next;
}

// Session-local saved/hidden state: the backend has no "is this saved" read or saved/hidden lists, so this
// reflects only what the viewer did since page load. Hidden items vanish from feeds and threads because the
// list/thread components render nothing for them; a reload gets the real filtering from the backend.
export function EngagementProvider({ children }: { children: React.ReactNode }) {
  const toast = useToast();
  const [saved, setSaved] = useState<Set<string>>(new Set());
  const [hidden, setHidden] = useState<Set<string>>(new Set());

  const isSaved = useCallback((type: TargetType, id: string) => saved.has(key(type, id)), [saved]);
  const isHidden = useCallback((type: TargetType, id: string) => hidden.has(key(type, id)), [hidden]);

  const toggleSave = useCallback(
    async (type: TargetType, id: string) => {
      const k = key(type, id);
      const wasSaved = saved.has(k);
      setSaved((s) => withKey(s, k, !wasSaved));
      try {
        await (wasSaved ? unsaveItem(type, id) : saveItem(type, id));
        toast.show(wasSaved ? `Removed from saved` : `Saved ${type}`);
      } catch (e) {
        setSaved((s) => withKey(s, k, wasSaved));
        toast.show(errMessage(e, `Could not ${wasSaved ? 'unsave' : 'save'} this ${type}.`));
      }
    },
    [saved, toast],
  );

  // Seeds the saved-set from a known-saved source (the /api/save listing itself) rather than a toggle
  // click, so a post's Save button reads "Unsave" as soon as the Saved page loads it — otherwise every
  // post there would show "Save" until clicked, since the set would otherwise only track this session's
  // own toggles.
  const markSaved = useCallback((type: TargetType, ids: string[]) => {
    setSaved((s) => {
      const next = new Set(s);
      ids.forEach((id) => next.add(key(type, id)));
      return next;
    });
  }, []);

  const hide = useCallback(
    async (type: TargetType, id: string): Promise<boolean> => {
      const k = key(type, id);
      setHidden((h) => withKey(h, k, true));
      try {
        await hideItem(type, id);
        toast.show(`${type === 'post' ? 'Post' : 'Comment'} hidden`, {
          label: 'Undo',
          onClick: () => {
            setHidden((h) => withKey(h, k, false));
            unhideItem(type, id).catch((e) => {
              setHidden((h) => withKey(h, k, true));
              toast.show(errMessage(e, 'Could not undo.'));
            });
          },
        });
        return true;
      } catch (e) {
        setHidden((h) => withKey(h, k, false));
        toast.show(errMessage(e, `Could not hide this ${type}.`));
        return false;
      }
    },
    [toast],
  );

  const value = useMemo(
    () => ({ isSaved, isHidden, toggleSave, hide, markSaved }),
    [isSaved, isHidden, toggleSave, hide, markSaved],
  );
  return <EngagementContext.Provider value={value}>{children}</EngagementContext.Provider>;
}

export function useEngagement(): EngagementContextValue {
  const ctx = useContext(EngagementContext);
  if (!ctx) throw new Error('useEngagement must be used inside EngagementProvider');
  return ctx;
}
