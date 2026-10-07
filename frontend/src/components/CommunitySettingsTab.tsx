import { useRef, useState, type FormEvent } from 'react';
import { useMediaUpload } from '../hooks/useMediaUpload';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../lib/apiClient';
import { updateCommunitySettings } from '../lib/communityApi';
import type { Community } from '../types/community';
import { DeleteCommunityDialog } from './DeleteCommunityDialog';
import styles from './CommunitySettingsTab.module.css';

interface CommunitySettingsTabProps {
  community: Community;
  canManage: boolean;
  onSaved: () => void;
}

const MAX_DESCRIPTION = 500;

function ImagePicker({
  label,
  currentUrl,
  upload,
  onRemove,
  removed,
}: {
  label: string;
  currentUrl: string | null | undefined;
  upload: ReturnType<typeof useMediaUpload>;
  onRemove: () => void;
  removed: boolean;
}) {
  const input = useRef<HTMLInputElement>(null);
  const shown = upload.previewUrl ?? (removed ? null : currentUrl);
  return (
    <div className={styles.field}>
      <span className={styles.label}>{label}</span>
      <div className={styles.imageRow}>
        {shown ? <img className={styles.preview} src={shown} alt={`${label} preview`} /> : <div className={styles.placeholder}>None</div>}
        <input
          ref={input}
          type="file"
          accept="image/jpeg,image/png,image/webp,image/gif"
          hidden
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) upload.selectFile(f);
            e.target.value = '';
          }}
        />
        <button type="button" className={styles.secondary} onClick={() => input.current?.click()} disabled={upload.uploading}>
          {upload.uploading ? 'Uploading…' : 'Choose image'}
        </button>
        {(shown || currentUrl) && !removed && (
          <button type="button" className={styles.secondary} onClick={onRemove}>
            Remove
          </button>
        )}
      </div>
      {upload.error && <p className={styles.error}>{upload.error}</p>}
    </div>
  );
}

export function CommunitySettingsTab({ community, canManage, onSaved }: CommunitySettingsTabProps) {
  const [description, setDescription] = useState(community.description ?? '');
  const icon = useMediaUpload();
  const banner = useMediaUpload();
  const [clearIcon, setClearIcon] = useState(false);
  const [clearBanner, setClearBanner] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);
  const { user } = useAuth();
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  // Only the creator sees the deletion control: not any moderator, not even one with every permission bit. The backend
  // enforces this too (403), so this is presentation, not authorization.
  const isCreator = !!user && user.id === community.creatorId;

  const dangerZone = (
    <section className={styles.dangerZone} aria-labelledby="community-danger-zone">
      <h3 id="community-danger-zone" className={styles.dangerTitle}>
        Danger Zone
      </h3>
      <p className={styles.dangerText}>
        Deleting this community makes it unavailable to everyone. Its existing posts, comments and history are retained, and
        the name r/{community.name} stays reserved. This cannot simply be undone through the site.
      </p>
      <button type="button" className={styles.dangerButton} onClick={() => setConfirmingDelete(true)}>
        Delete Community
      </button>
      {confirmingDelete && <DeleteCommunityDialog communityName={community.name} onClose={() => setConfirmingDelete(false)} />}
    </section>
  );

  if (!canManage) {
    return (
      <>
        <p className={styles.note}>You don't have permission to change this community's settings.</p>
        {isCreator && dangerZone}
      </>
    );
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (icon.uploading || banner.uploading) {
      setMessage({ kind: 'error', text: 'Please wait for the image upload to finish.' });
      return;
    }
    setSaving(true);
    setMessage(null);
    try {
      await updateCommunitySettings(community.name, {
        description,
        iconMediaId: icon.mediaId ?? undefined,
        bannerMediaId: banner.mediaId ?? undefined,
        clearIcon: !icon.mediaId && clearIcon,
        clearBanner: !banner.mediaId && clearBanner,
      });
      setMessage({ kind: 'ok', text: 'Settings saved.' });
      onSaved();
    } catch (err) {
      setMessage({ kind: 'error', text: err instanceof ApiError ? err.message : 'Could not save settings.' });
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
    <form className={styles.form} onSubmit={handleSubmit}>
      <div className={styles.field}>
        <label className={styles.label} htmlFor="community-description">
          Description <span className={styles.count}>{description.length}/{MAX_DESCRIPTION}</span>
        </label>
        <textarea
          id="community-description"
          className={styles.textarea}
          value={description}
          maxLength={MAX_DESCRIPTION}
          onChange={(e) => setDescription(e.target.value)}
        />
      </div>
      <ImagePicker
        label="Icon"
        currentUrl={community.iconUrl}
        upload={icon}
        removed={clearIcon && !icon.mediaId}
        onRemove={() => {
          icon.reset();
          setClearIcon(true);
        }}
      />
      <ImagePicker
        label="Banner"
        currentUrl={community.bannerUrl}
        upload={banner}
        removed={clearBanner && !banner.mediaId}
        onRemove={() => {
          banner.reset();
          setClearBanner(true);
        }}
      />
      <button type="submit" className={styles.primary} disabled={saving}>
        {saving ? 'Saving…' : 'Save changes'}
      </button>
      {message && <p className={message.kind === 'ok' ? styles.ok : styles.error}>{message.text}</p>}
    </form>
    {isCreator && dangerZone}
    </>
  );
}
