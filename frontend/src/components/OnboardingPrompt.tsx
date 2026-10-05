import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { OnboardingStatus } from '../api/types';
import { Card } from './ui';

/**
 * V9.12: on the dashboard, sends an account that has not started onboarding to it, and offers a
 * skipped onboarding until it is finished. A completed one shows nothing, and so does any error:
 * onboarding must never stand between a user and the dashboard.
 */
export function OnboardingPrompt() {
  const [status, setStatus] = useState<OnboardingStatus | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    let active = true;
    api.onboarding().then((next) => {
      if (!active) return;
      if (next.status === 'PENDING') {
        navigate('/onboarding', { replace: true });
      } else {
        setStatus(next);
      }
    }, () => undefined);
    return () => {
      active = false;
    };
  }, [navigate]);

  if (!status || status.status !== 'SKIPPED') {
    return null;
  }
  return (
    <Card className="onboarding-prompt" title="Finish setting up JMIP"
      description={`${status.completedSteps} of ${status.totalSteps} steps done. A few answers personalize your job matches, skill gaps and dashboard.`}
      actions={<Link className="button-link" to="/onboarding">Continue setup</Link>}>
      <span className="visually-hidden">Onboarding progress: {status.completedSteps} of {status.totalSteps}</span>
    </Card>
  );
}
