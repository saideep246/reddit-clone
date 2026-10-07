import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../lib/apiClient';
import styles from './AuthForm.module.css';

export function VerifyEmail() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');

  const [verifying, setVerifying] = useState(Boolean(token));
  const [verified, setVerified] = useState(false);
  const [verifyError, setVerifyError] = useState<string | null>(null);
  // StrictMode/effect re-runs would otherwise send the same single-use token twice, turning the second
  // call's "already used" response into a spurious error on an actually-successful verification.
  const attempted = useRef(false);

  useEffect(() => {
    if (!token || attempted.current) return;
    attempted.current = true;
    (async () => {
      try {
        await api.post('/api/v1/verify-email', { token });
        setVerified(true);
      } catch (err) {
        setVerifyError(err instanceof ApiError ? err.message : 'Something went wrong. Please try again.');
      } finally {
        setVerifying(false);
      }
    })();
  }, [token]);

  const [email, setEmail] = useState('');
  const [resendError, setResendError] = useState<string | null>(null);
  const [resending, setResending] = useState(false);
  const [resent, setResent] = useState(false);

  const handleResend = async (e: FormEvent) => {
    e.preventDefault();
    setResendError(null);
    setResending(true);
    try {
      await api.post('/api/v1/verify-email/resend', { email });
      setResent(true);
    } catch (err) {
      setResendError(err instanceof ApiError ? err.message : 'Something went wrong. Please try again.');
    } finally {
      setResending(false);
    }
  };

  return (
    <div className={styles.wrapper}>
      <div className={styles.card}>
        <h1 className={styles.title}>Verify Email</h1>

        {token ? (
          verifying ? (
            <p className={styles.success}>Verifying…</p>
          ) : verified ? (
            <p className={styles.success}>
              Your email is verified. You can now <Link to="/login">log in</Link>.
            </p>
          ) : (
            <p className={styles.error}>{verifyError}</p>
          )
        ) : null}

        {(!token || (!verifying && !verified)) && (
          <>
            {token && <p className={styles.footer}>Need a new link?</p>}
            {resent ? (
              <p className={styles.success}>If that account exists and isn't verified yet, we've sent a new link.</p>
            ) : (
              <form onSubmit={handleResend}>
                {resendError && <p className={styles.error}>{resendError}</p>}
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
                <button type="submit" className={styles.submit} disabled={resending}>
                  {resending ? 'Sending…' : 'Resend Verification Email'}
                </button>
              </form>
            )}
          </>
        )}

        <p className={styles.footer}>
          <Link to="/login">Back to Log In</Link>
        </p>
      </div>
    </div>
  );
}
