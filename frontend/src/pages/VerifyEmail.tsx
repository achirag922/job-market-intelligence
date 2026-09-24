import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { AuthLayout, maskEmail } from '../auth/AuthLayout';
import { useAuth } from '../auth/AuthContext';
import { motionAllowed, OtpInput, type OtpMotion } from '../auth/OtpInput';

const COMPLETE_CODE = /^\d{6}$/;
/** The backend's cooldown; the resend response gives the authoritative remaining time. */
const RESEND_COOLDOWN_SECONDS = 60;
/** How long the success screen shows before continuing on its own. */
const SUCCESS_PAUSE_MS = 900;
const SUCCESS_PAUSE_ANIMATED_MS = 2200;
/** Beats of the verdict, shown on the orbit before the boxes move on. */
const VERDICT_HOLD_MS = 420;
const PARTICLES = 14;

type Phase = 'idle' | 'verifying' | 'confirmed' | 'success' | 'error';
type Outcome = 'signedIn' | 'needsLogin';

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

  // The orbit choreography runs only where it can (and where motion is welcome); otherwise
  // every step below resolves at once and the screen behaves as a plain form.
  const [animated] = useState(motionAllowed);
  const [motion, setMotion] = useState<OtpMotion>('row');
  const motionWaiter = useRef<{ target: OtpMotion; resolve: () => void } | null>(null);
  const outcome = useRef<Outcome>('needsLogin');
  const continueTimer = useRef<number | undefined>(undefined);

  useEffect(() => {
    if (secondsLeft <= 0) {
      return;
    }
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, [secondsLeft]);

  useEffect(() => () => clearTimeout(continueTimer.current), []);

  const runMotion = useCallback(
    (target: OtpMotion) =>
      animated
        ? new Promise<void>((resolve) => {
            motionWaiter.current = { target, resolve };
            setMotion(target);
          })
        : Promise.resolve(),
    [animated],
  );
  const onMotionEnd = useCallback((settled: OtpMotion) => {
    if (motionWaiter.current?.target === settled) {
      const { resolve } = motionWaiter.current;
      motionWaiter.current = null;
      resolve();
    }
  }, []);
  const pause = useCallback(
    (ms: number) => (animated ? new Promise<void>((resolve) => setTimeout(resolve, ms)) : Promise.resolve()),
    [animated],
  );

  const proceed = useCallback(() => {
    clearTimeout(continueTimer.current);
    if (outcome.current === 'signedIn') {
      navigate(returnTo, { replace: true });
    } else {
      navigate('/login', {
        replace: true,
        state: { from: returnTo, notice: 'Email verified. Log in to continue.', email },
      });
    }
  }, [navigate, returnTo, email]);

  const submit = useCallback(
    async (candidate: string) => {
      if (!COMPLETE_CODE.test(candidate) || phase === 'verifying' || phase === 'confirmed' || phase === 'success') {
        return;
      }
      setPhase('verifying');
      setMessage(null);
      setNotice(null);
      // The row curls onto the orbit and spins while the code is checked.
      const orbit = runMotion('orbit');
      try {
        const [result] = await Promise.all([verifyEmail(candidate), orbit]);
        outcome.current = result;
      } catch (failure) {
        await orbit;
        setPhase('error');
        setMessage(failure instanceof ApiError ? failure.message : 'Verification failed. Please try again.');
        await pause(VERDICT_HOLD_MS);
        await runMotion('row');
        return;
      }
      setPhase('confirmed');
      await pause(VERDICT_HOLD_MS);
      await runMotion('collapse');
      setPhase('success');
      continueTimer.current = window.setTimeout(proceed, animated ? SUCCESS_PAUSE_ANIMATED_MS : SUCCESS_PAUSE_MS);
    },
    [phase, verifyEmail, runMotion, pause, proceed, animated],
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

  if (phase === 'success') {
    return (
      <AuthLayout title="Email verified" tone="success" subtitle="Your email has been verified.">
        <div className="auth-form">
          <div className="verified" role="status">
            <div className="verified-badge" aria-hidden="true">
              <span className="verified-ring verified-ring-outer" />
              <span className="verified-ring verified-ring-inner" />
              <span className="verified-core">
                <svg viewBox="0 0 24 24" className="verified-check" focusable="false">
                  <path d="m6.5 12.5 3.5 3.5 7.5-8" pathLength={1} />
                </svg>
              </span>
              {Array.from({ length: PARTICLES }, (_, index) => {
                const angle = (index / PARTICLES) * Math.PI * 2 + (index % 3) * 0.35;
                const distance = 58 + (index % 4) * 12;
                return (
                  <span
                    key={index}
                    className="verified-particle"
                    style={{
                      '--x': `${(Math.cos(angle) * distance).toFixed(1)}px`,
                      '--y': `${(Math.sin(angle) * distance).toFixed(1)}px`,
                      '--delay': `${(index % 5) * 40}ms`,
                    } as CSSProperties}
                  />
                );
              })}
            </div>
            <span className="visually-hidden">Email verified</span>
          </div>
          <p className="verified-secure">
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <rect x="5" y="10.5" width="14" height="10" rx="2.5" />
              <path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5" />
            </svg>
            Verified and secure
          </p>
          <button type="button" className="auth-submit auth-continue" onClick={proceed}>
            Continue
          </button>
        </div>
      </AuthLayout>
    );
  }

  const needsNewCode = Boolean(message && /expired|too many/i.test(message));
  const busy = phase === 'verifying' || phase === 'confirmed';

  return (
    <AuthLayout
      title="Verify your email"
      subtitle={
        <>
          Enter the 6-digit code we sent to your Gmail address.
          <span className="auth-email" id="otp-help">
            {maskEmail(email)}
          </span>
        </>
      }
    >
      <div className="auth-form">
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
          disabled={busy}
          invalid={phase === 'error'}
          success={phase === 'confirmed'}
          autoFocus
          describedBy="otp-help"
          motion={motion}
          onMotionEnd={onMotionEnd}
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

        <button
          type="button"
          className="auth-submit"
          disabled={!COMPLETE_CODE.test(code) || busy}
          onClick={() => void submit(code)}
        >
          {busy ? <span className="auth-spinner" aria-hidden="true" /> : null}
          {busy ? 'Verifying…' : 'Verify'}
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
      </div>
    </AuthLayout>
  );
}

function formatCountdown(seconds: number): string {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
