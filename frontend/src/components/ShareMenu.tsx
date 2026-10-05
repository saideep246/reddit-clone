import { useEffect, useRef, useState } from 'react';
import type { Post } from '../types/post';
import { ShareIcon } from './icons';
import styles from './PostActionBar.module.css';

// The short share link: /p/<postId> redirects to the canonical thread URL (see pages/PostRedirect).
export function shareUrl(post: Pick<Post, 'id'>): string {
  return `${window.location.origin}/p/${post.id}`;
}

export function ShareMenu({ post }: { post: Pick<Post, 'id' | 'title'> }) {
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(shareUrl(post));
      setCopied(true);
      setTimeout(() => {
        setCopied(false);
        setOpen(false);
      }, 1200);
    } catch {
      window.prompt('Copy this link', shareUrl(post));
      setOpen(false);
    }
  };

  const nativeShare = async () => {
    try {
      await navigator.share({ title: post.title, url: shareUrl(post) });
    } catch {
      // The user dismissed the share sheet — nothing to do.
    }
    setOpen(false);
  };

  return (
    <div className={styles.menuWrap} ref={ref} onClick={(e) => e.stopPropagation()}>
      <button type="button" className={styles.pill} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <ShareIcon />
        <span>Share</span>
      </button>
      {open && (
        <div className={styles.menu} role="menu">
          <button type="button" role="menuitem" className={styles.menuItem} onClick={copy}>
            {copied ? 'Link copied!' : 'Copy link'}
          </button>
          {typeof navigator.share === 'function' && (
            <button type="button" role="menuitem" className={styles.menuItem} onClick={nativeShare}>
              Share via…
            </button>
          )}
          <a
            role="menuitem"
            className={styles.menuItem}
            href={`https://twitter.com/intent/tweet?url=${encodeURIComponent(shareUrl(post))}&text=${encodeURIComponent(post.title)}`}
            target="_blank"
            rel="noreferrer noopener"
            onClick={() => setOpen(false)}
          >
            Share on X
          </a>
        </div>
      )}
    </div>
  );
}
