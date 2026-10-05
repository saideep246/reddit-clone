import { useEffect, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { AutomodTab } from '../components/AutomodTab';
import { BansTab } from '../components/BansTab';
import { JoinRequestsTab } from '../components/JoinRequestsTab';
import { CommunitySettingsTab } from '../components/CommunitySettingsTab';
import { ModLogTab } from '../components/ModLogTab';
import { ModNotesTab } from '../components/ModNotesTab';
import { ModQueueTab } from '../components/ModQueueTab';
import { fetchCommunityAbout } from '../lib/communityApi';
import {
  hasPermission,
  PERM_BAN_USERS,
  PERM_MANAGE_ACCESS,
  PERM_MANAGE_AUTOMOD,
  PERM_MANAGE_SETTINGS,
} from '../types/moderation';
import type { Community } from '../types/community';
import styles from './ModerationDashboard.module.css';

type Tab = 'queue' | 'bans' | 'join-requests' | 'automod' | 'settings' | 'notes' | 'log';

export function ModerationDashboard() {
  const { communityName = '' } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = (searchParams.get('tab') as Tab) || 'queue';

  const [community, setCommunity] = useState<Community | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    if (reloadKey === 0) setLoading(true);
    setError(null);

    (async () => {
      try {
        const c = await fetchCommunityAbout(communityName);
        if (!cancelled) setCommunity(c);
      } catch {
        if (!cancelled) setError('Could not load this community.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [communityName, reloadKey]);

  const setTab = (next: Tab) => {
    const params = new URLSearchParams(searchParams);

    if (next === 'queue') {
      params.delete('tab');
    } else {
      params.set('tab', next);
    }

    setSearchParams(params);
  };

  if (loading) {
    return <div className={styles.state}>Loading…</div>;
  }

  if (error || !community) {
    return <div className={styles.state}>{error ?? 'Community not found.'}</div>;
  }

  if (!community.isModerator) {
    return <div className={styles.state}>You're not a moderator of r/{communityName}.</div>;
  }

  const canSeeBans = hasPermission(community.myPermissions, PERM_BAN_USERS);
  const canSeeJoinRequests = hasPermission(community.myPermissions, PERM_MANAGE_ACCESS);
  const canManageAutomod = hasPermission(community.myPermissions, PERM_MANAGE_AUTOMOD);
  const canManageSettings = hasPermission(community.myPermissions, PERM_MANAGE_SETTINGS);

  // Tabs a capped moderator lacks the view permission for simply aren't offered — the backend itself
  // 403s the underlying list for the exact same bit, so this isn't just cosmetic.
  const activeTab: Tab =
    (tab === 'bans' && !canSeeBans) ||
    (tab === 'join-requests' && !canSeeJoinRequests) ||
    (tab === 'settings' && !canManageSettings)
      ? 'queue'
      : tab;

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Mod Tools — r/{communityName}</h1>

      <div className={styles.tabs}>
        <button
          type="button"
          className={`${styles.tab} ${activeTab === 'queue' ? styles.tabActive : ''}`}
          onClick={() => setTab('queue')}
        >
          Queue
        </button>

        {canSeeBans && (
          <button
            type="button"
            className={`${styles.tab} ${activeTab === 'bans' ? styles.tabActive : ''}`}
            onClick={() => setTab('bans')}
          >
            Bans
          </button>
        )}

        {canSeeJoinRequests && (
          <button
            type="button"
            className={`${styles.tab} ${activeTab === 'join-requests' ? styles.tabActive : ''}`}
            onClick={() => setTab('join-requests')}
          >
            Join Requests
          </button>
        )}

        <button
          type="button"
          className={`${styles.tab} ${activeTab === 'automod' ? styles.tabActive : ''}`}
          onClick={() => setTab('automod')}
        >
          Automod
        </button>
                {canManageSettings && (
                  <button
                    type="button"
                    className={`${styles.tab} ${activeTab === 'settings' ? styles.tabActive : ''}`}
                    onClick={() => setTab('settings')}
                  >
                    Settings
                  </button>
                )}

                <button
                  type="button"
                  className={`${styles.tab} ${activeTab === 'notes' ? styles.tabActive : ''}`}
                  onClick={() => setTab('notes')}
                >
                  Notes
                </button>

                <button
                  type="button"
                  className={`${styles.tab} ${activeTab === 'log' ? styles.tabActive : ''}`}
                  onClick={() => setTab('log')}
                >
                  Log
                </button>
              </div>

              {activeTab === 'queue' && (
                <ModQueueTab
                  communityName={communityName}
                  myPermissions={community.myPermissions}
                />
              )}

              {activeTab === 'bans' && canSeeBans && (
                <BansTab communityName={communityName} />
              )}

              {activeTab === 'join-requests' && canSeeJoinRequests && (
                <JoinRequestsTab communityName={communityName} />
              )}

              {activeTab === 'automod' && (
                <AutomodTab
                  communityName={communityName}
                  canManage={canManageAutomod}
                />
              )}

              {activeTab === 'notes' && <ModNotesTab communityName={communityName} />}

              {activeTab === 'log' && <ModLogTab communityName={communityName} />}
      {activeTab === 'settings' && (
        <CommunitySettingsTab
          community={community}
          canManage={canManageSettings}
          onSaved={() => setReloadKey((k) => k + 1)}
        />
      )}
    </div>
  );
}