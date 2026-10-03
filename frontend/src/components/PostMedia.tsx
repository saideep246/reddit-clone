import { useState } from 'react';
import { decodeHtmlEntities } from '../lib/html';
import { useSettings } from '../settings/SettingsContext';
import { CrosspostEmbed } from './CrosspostEmbed';
import { PollBlock } from './PollBlock';
import type { Post } from '../types/post';
import styles from './PostMedia.module.css';

interface PostMediaProps {
  post: Post;
  // false (default, feed cards): clamps text-post bodies to 3 lines. true (post detail page): shows the
  // complete body.
  fullBody?: boolean;
}

function domainOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

function renderContent(post: Post, fullBody: boolean) {
  if (post.kind === 'image' || post.kind === 'video') {
    if (post.media?.processingStatus === 'ready' && post.media.thumbnailUrl) {
      return post.kind === 'video' ? (
        <video className={styles.thumbnail} src={post.media.displayUrl ?? undefined} controls />
      ) : (
        <img className={styles.thumbnail} src={post.media.thumbnailUrl} alt="" />
      );
    }
    return <div className={styles.mediaPlaceholder}>{post.kind === 'video' ? 'Video processing…' : 'Image processing…'}</div>;
  }
  if (post.kind === 'gallery' && post.mediaItems && post.mediaItems.length > 0) {
    if (!fullBody) {
      // Feed card: first image only, same treatment as a single-image post, plus a "+N" badge when
      // there's more than one — matches real Reddit's own feed-vs-detail gallery treatment.
      const first = post.mediaItems[0];
      return (
        <div className={styles.galleryPreview}>
          {first.processingStatus === 'ready' && first.thumbnailUrl ? (
            <img className={styles.thumbnail} src={first.thumbnailUrl} alt="" />
          ) : (
            <div className={styles.mediaPlaceholder}>Image processing…</div>
          )}
          {post.mediaItems.length > 1 && <span className={styles.galleryCountBadge}>+{post.mediaItems.length - 1}</span>}
        </div>
      );
    }
    // Detail page: the full ordered strip, scrollable since a gallery can have up to 20 images.
    return (
      <div className={styles.galleryStrip}>
        {post.mediaItems.map((item, index) =>
          item.processingStatus === 'ready' && item.thumbnailUrl ? (
            <img key={index} className={styles.galleryStripImage} src={item.thumbnailUrl} alt="" loading="lazy" />
          ) : (
            <div key={index} className={styles.galleryStripPlaceholder}>
              Image processing…
            </div>
          ),
        )}
      </div>
    );
  }
  if (post.kind === 'poll') {
    return <PollBlock post={post} interactive={fullBody} />;
  }
  if (post.kind === 'crosspost') {
    return <CrosspostEmbed parent={post.crosspostParent} />;
  }
  if (post.kind === 'link' && post.url) {
    return <div className={styles.domain}>({domainOf(post.url)})</div>;
  }
  if (post.kind === 'text' && post.body) {
    const snippetClass = fullBody ? styles.snippet : `${styles.snippet} ${styles.snippetTruncated}`;
    return <p className={snippetClass}>{decodeHtmlEntities(post.body)}</p>;
  }
  return null;
}

export function PostMedia({ post, fullBody = false }: PostMediaProps) {
  const { nsfwBlurEffective } = useSettings();
  const [revealed, setRevealed] = useState(false);
  const content = renderContent(post, fullBody);
  if (content === null) return null;

  if (post.nsfw && nsfwBlurEffective && !revealed) {
    return (
      <div className={styles.nsfwWrapper}>
        <div className={styles.nsfwBlurred}>{content}</div>
        <button type="button" className={styles.nsfwReveal} onClick={() => setRevealed(true)}>
          NSFW — click to view
        </button>
      </div>
    );
  }
  return content;
}
