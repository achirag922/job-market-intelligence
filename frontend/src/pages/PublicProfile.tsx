import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type { PublicProfile } from '../api/types';
import { ProfileView } from '../components/ProfileView';

/** V9.7: a published profile at /profile/{slug}, outside the app shell and without signing in. */
export function PublicProfilePage() {
  const { slug = '' } = useParams();
  const [profile, setProfile] = useState<PublicProfile | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setProfile(null);
    setError(null);
    api.publicProfile(slug).then(setProfile, (cause: unknown) =>
      setError(cause instanceof ApiError && cause.status === 404
        ? 'This profile does not exist or is not public.'
        : 'The profile could not be loaded. Please try again.'));
  }, [slug]);

  useEffect(() => {
    document.title = profile ? `${profile.displayName} · Profile` : 'Profile';
  }, [profile]);

  return (
    <main className="public-profile">
      {error ? (
        <div className="state-panel" role="alert">
          <p className="state-panel-title">Profile unavailable</p>
          <p className="muted">{error}</p>
        </div>
      ) : !profile ? (
        <p className="muted">Loading profile…</p>
      ) : (
        <ProfileView profile={profile} />
      )}
    </main>
  );
}
