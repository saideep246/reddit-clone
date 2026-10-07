import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../lib/apiClient';
import styles from './AuthForm.module.css';

export function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // The backend always responds 200 whether or not the email is registered (so this page can't be used
  // to probe which emails have accounts) — this just reflects that one generic outcome.
  const [sent, setSent] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await api.post('/api/v1/password-reset/request', { email });
      setSent(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Something went wrong. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className={styles.wrapper}>
      <div className={styles.card}>
        <h1 className={styles.title}>Forgot Password</h1>
        {error && <p className={styles.error}>{error}</p>}
        {sent ? (
          <p className={styles.success}>If an account with that email exists, we've sent a link to reset your password.</p>
        ) : (
          <form onSubmit={handleSubmit}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="email">
                Email
              </label>
              <input
                id="email"
                className={styles.input}
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="email"
                required
              />
            </div>
            <button type="submit" className={styles.submit} disabled={submitting}>
              {submitting ? 'Sending…' : 'Send Reset Link'}
            </button>
          </form>
        )}
        <p className={styles.footer}>
          <Link to="/login">Back to Log In</Link>
        </p>
      </div>
    </div>
  );
}
