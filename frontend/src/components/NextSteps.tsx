import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { ReadinessComponent } from '../api/types';
import { Card } from './ui';

/** Where each readiness component is improved. */
const ACTIONS: Record<string, { label: string; to: string }> = {
  profile: { label: 'Complete your profile', to: '/onboarding' },
  resume: { label: 'Upload or update your resume', to: '/resume' },
  jobSearch: { label: 'Explore recommended jobs', to: '/for-you' },
  skills: { label: 'Improve your skill gap', to: '/career-goals' },
  learning: { label: 'Continue learning', to: '/learning' },
  interviews: { label: 'Practise an interview', to: '/interview-prep' },
  portfolio: { label: 'Update your portfolio', to: '/portfolio' },
};

const SHOWN = 4;

/**
 * V9.15: the dashboard's next actions, chosen from the V9.13 readiness score: only parts that are
 * not complete appear, largest gap first, each with the server's own hint. Nothing shows while it
 * loads or if it cannot be read, so the dashboard never waits on it.
 */
export function NextSteps() {
  const [components, setComponents] = useState<ReadinessComponent[] | null>(null);

  useEffect(() => {
    let active = true;
    api.careerProgress?.().then((progress) => active && setComponents(progress.readiness.components), () => undefined);
    return () => {
      active = false;
    };
  }, []);

  if (!components) {
    return null;
  }
  const open = components.filter((c) => c.points < c.maxPoints && ACTIONS[c.key])
    .sort((a, b) => (b.maxPoints - b.points) - (a.maxPoints - a.points))
    .slice(0, SHOWN);

  return (
    <Card className="next-steps" title={open.length > 0 ? 'Your next steps' : 'You are all set'}
      description={open.length > 0
        ? 'Chosen from what is still incomplete in your career readiness.'
        : 'Every part of your career readiness is complete. Keep your search and learning going.'}
      actions={<Link to="/progress">See your progress</Link>}>
      {open.length > 0 && (
        <ul className="next-steps-list" aria-label="Next steps">
          {open.map((component) => (
            <li key={component.key}>
              <Link className="next-step" to={ACTIONS[component.key].to}>
                <strong>{ACTIONS[component.key].label}</strong>
                <span className="muted small">{component.hint ?? component.detail}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <p className="muted small" style={{ margin: '8px 0 0' }}>
        <Link to="/welcome#privacy">How JMIP uses your data</Link>
      </p>
    </Card>
  );
}
