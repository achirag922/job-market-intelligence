import { useCallback, useEffect, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { AuthLayout, maskEmail } from '../auth/AuthLayout';
import { useAuth } from '../auth/AuthContext';
import { OtpInput } from '../auth/OtpInput';

const COMPLETE_CODE = /^\d{6}$/;
/** The backend's cooldown; the resend response gives the authoritative remaining time. */
const RESEND_COOLDOWN_SECONDS = 60;
const SUCCESS_PAUSE_MS = 900;

type Phase = 'idle' | 'verifying' | 'success' | 'error';

/** Enter the 6-digit code from the verification email. */
export function VerifyEmail() {
  const { pendingVerification, verifyEmail, resendVerification } = useAuth();
  const navigate = useNavigate();
  const returnTo = (useLocation().state as { from?: string } | null)?.from ?? '/';

  // Captured once: signing in after success clears the pending state, and this screen
  // must not mistake that for "nothing to verify".
  const [email] = useState(() => pendingVerification?.email ?? '');
  const [code, setCode] = useState('');
  const [phase, setPhase] = useState<Phase>('idle');
  const [message, setMessage] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  // A deadline rather than a decrementing counter: browsers slow timers in background tabs,
  // and the remaining time must still be right when the user comes back.
  const [resendAt, setResendAt] = useState(() => Date.now() + RESEND_COOLDOWN_SECONDS * 1000);
  const [now, setNow] = useState(() => Date.now());
  const [resending, setResending] = useState(false);
  const secondsLeft = Math.max(0, Math.ceil((resendAt - now) / 1000));

  useEffect(() => {
    if (secondsLeft <= 0) {
      return;
    }
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, [secondsLeft]);

  const submit = useCallback(
    async (candidate: string) => {
      if (!COMPLETE_CODE.test(candidate) || phase === 'verifying' || phase === 'success') {
        return;
      }
      setPhase('verifying');
      setMessage(null);
      setNotice(null);
      try {
        const outcome = await verifyEmail(candidate);
        setPhase('success');
        setTimeout(() => {
          if (outcome === 'signedIn') {
            navigate(returnTo, { replace: true });
          } else {
            navigate('/login', {
              replace: true,
              state: { from: returnTo, notice: 'Email verified. Log in to continue.', email },
            });
          }
        }, SUCCESS_PAUSE_MS);
      } catch (failure) {
        setPhase('error');
        setMessage(failure instanceof ApiError ? failure.message : 'Verification failed. Please try again.');
      }
    },
    [phase, verifyEmail, navigate, returnTo, email],
  );

  const resend = async () => {
    setResending(true);
    setMessage(null);
    try {
      const status = await resendVerification();
      const wait = status.resendAvailableInSeconds > 0 ? status.resendAvailableInSeconds : RESEND_COOLDOWN_SECONDS;
      setNow(Date.now());
      setResendAt(Date.now() + wait * 1000);
      setCode('');
      setPhase('idle');
      setNotice('A new code is on its way. Check your inbox.');
    } catch (failure) {
      setMessage(failure instanceof ApiError ? failure.message : 'Could not send a new code. Please try again.');
      setPhase('error');
    } finally {
      setResending(false);
    }
  };

  // Reached directly (reload, bookmark): nothing is waiting, so start from Login.
  if (!email) {
    return <Navigate to="/login" replace state={{ notice: 'Log in to get a verification code.' }} />;
  }

  const needsNewCode = Boolean(message && /expired|too many/i.test(message));

  return (
    <AuthLayout
      title={phase === 'success' ? 'Email verified' : 'Verify your email'}
      subtitle={
        phase === 'success' ? (
          'You are all set. Taking you to your dashboard…'
        ) : (
          <>
            Enter the 6-digit code we sent to your Gmail address.
            <span className="auth-email" id="otp-help">
              {maskEmail(email)}
            </span>
          </>
        )
      }
    >
      <div className="auth-form">
        {phase === 'success' ? (
          <div className="auth-success" role="status">
            <svg viewBox="0 0 24 24" aria-hidden="true" className="auth-success-icon">
              <circle cx="12" cy="12" r="10" />
              <path d="m7.5 12.5 3 3 6-6.5" />
            </svg>
            <span className="visually-hidden">Email verified</span>
          </div>
        ) : null}

        <OtpInput
          value={code}
          onChange={(next) => {
            setCode(next);
            if (phase === 'error') {
              setPhase('idle');
              setMessage(null);
            }
          }}
          onComplete={submit}
          disabled={phase === 'verifying' || phase === 'success'}
          invalid={phase === 'error'}
          success={phase === 'success'}
          autoFocus
          describedBy="otp-help"
        />

        {message && (
          <p className="auth-alert" role="alert">
            {message}
          </p>
        )}
        {notice && !message && (
          <p className="auth-notice" role="status">
            {notice}
          </p>
        )}

        {phase !== 'success' && (
          <>
            <button
              type="button"
              className="auth-submit"
              disabled={!COMPLETE_CODE.test(code) || phase === 'verifying'}
              onClick={() => void submit(code)}
            >
              {phase === 'verifying' ? <span className="auth-spinner" aria-hidden="true" /> : null}
              {phase === 'verifying' ? 'Verifying…' : 'Verify'}
            </button>
            <p className="auth-switch" aria-live="polite">
              Didn&apos;t get it?{' '}
              {secondsLeft > 0 && !needsNewCode ? (
                <span className="auth-countdown">Resend OTP in {formatCountdown(secondsLeft)}</span>
              ) : (
                <button type="button" className="auth-link-button" onClick={() => void resend()} disabled={resending}>
                  {resending ? 'Sending…' : 'Resend OTP'}
                </button>
              )}
            </p>
          </>
        )}
      </div>
    </AuthLayout>
  );
}

function formatCountdown(seconds: number): string {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
