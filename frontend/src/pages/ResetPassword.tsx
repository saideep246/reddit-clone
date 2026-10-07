import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../lib/apiClient';
import styles from './AuthForm.module.css';

export function ResetPassword() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const navigate = useNavigate();
  const [newPassword, setNewPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await api.post('/api/v1/password-reset/confirm', { token, newPassword });
      setDone(true);
      setTimeout(() => navigate('/login'), 2000);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Something went wrong. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className={styles.wrapper}>
      <div className={styles.card}>
        <h1 className={styles.title}>Reset Password</h1>
        {!token ? (
          <p className={styles.error}>
            This link is missing its token. Request a new one from{' '}
            <Link to="/forgot-password">Forgot Password</Link>.
          </p>
        ) : done ? (
          <p className={styles.success}>Your password has been reset. Redirecting to log in…</p>
        ) : (
          <>
            {error && <p className={styles.error}>{error}</p>}
            <form onSubmit={handleSubmit}>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="newPassword">
                  New Password
                </label>
                <input
                  id="newPassword"
                  className={styles.input}
                  type="password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  autoComplete="new-password"
                  minLength={8}
                  maxLength={200}
                  required
                />
              </div>
              <button type="submit" className={styles.submit} disabled={submitting}>
                {submitting ? 'Resetting…' : 'Reset Password'}
              </button>
            </form>
          </>
        )}
        <p className={styles.footer}>
          <Link to="/login">Back to Log In</Link>
        </p>
      </div>
    </div>
  );
}
