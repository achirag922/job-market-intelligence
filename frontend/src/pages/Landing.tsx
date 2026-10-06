import { useEffect } from 'react';
import type { ComponentType } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import {
  IconBriefcase,
  IconChat,
  IconDashboard,
  IconFile,
  IconFlag,
  IconSpark,
  IconTrend,
} from '../components/icons';

type Icon = ComponentType<{ size?: number }>;

const FEATURES: { title: string; text: string; icon: Icon }[] = [
  { title: 'Personalized job matching', text: 'Jobs ranked by how well they fit your resume, preferences and career goal, with the reasons shown.', icon: IconBriefcase },
  { title: 'Resume intelligence', text: 'See the skills your resume shows, compare versions, build one in the browser and tailor it to a job.', icon: IconFile },
  { title: 'Skill gap & learning', text: 'A roadmap of the skills your target role asks for, and a learning plan to close the gap.', icon: IconSpark },
  { title: 'Interview preparation', text: 'Practice interviews for a specific job, with feedback on every answer and a final report.', icon: IconChat },
  { title: 'Career analytics', text: 'Your applications, match scores, interviews and learning over time, plus market trends for your role.', icon: IconTrend },
  { title: 'Professional portfolio', text: 'A profile built from your own resume, private until you publish it at your own address.', icon: IconFlag },
];

const WORKFLOW = [
  { step: 'Sign up', text: 'Verify your email with a one-time code.' },
  { step: 'Onboarding', text: 'Your target role, experience and preferences.' },
  { step: 'Resume', text: 'Upload or build it; skills are read automatically.' },
  { step: 'Personalized jobs', text: 'Recommendations ranked for you.' },
  { step: 'Match analysis', text: 'What you have and what each job is missing.' },
  { step: 'Skill gap', text: 'The skills your target role needs next.' },
  { step: 'Learning', text: 'Plan, track and complete them.' },
  { step: 'Interview', text: 'Practice with feedback.' },
  { step: 'Analytics', text: 'Watch your progress over time.' },
  { step: 'Portfolio', text: 'Share a professional profile.' },
];

/**
 * V9.15: the public landing page: what JMIP does, the workflow, how it uses your data, and the way in.
 * It describes features only; it shows no job or user data, real or invented.
 */
export function Landing() {
  const { status } = useAuth();
  const signedIn = status === 'signedIn';

  useEffect(() => {
    document.title = 'JMIP · Job Market Intelligence';
  }, []);

  return (
    <div className="landing">
      <header className="landing-header">
        <p className="brand">
          <span className="brand-mark" aria-hidden="true"><IconDashboard size={16} /></span>
          JMIP
        </p>
        <nav className="landing-header-actions" aria-label="Account">
          {signedIn ? (
            <Link className="button-link primary" to="/">Go to dashboard</Link>
          ) : (
            <>
              <Link className="button-link" to="/login">Login</Link>
              <Link className="button-link primary" to="/signup">Get Started</Link>
            </>
          )}
        </nav>
      </header>

      <main id="main-content">
        <section className="landing-hero" aria-labelledby="landing-title">
          <h1 id="landing-title">Your job search and career growth, in one place</h1>
          <p className="landing-lead">
            JMIP matches you with jobs that fit your resume and goals, shows the skills you are missing,
            helps you learn them and practise interviews, and tracks your progress.
          </p>
          <div className="landing-cta">
            <Link className="button-link primary landing-cta-main" to={signedIn ? '/' : '/signup'}>
              {signedIn ? 'Go to dashboard' : 'Get Started'}
            </Link>
            {!signedIn && <Link className="button-link" to="/login">Login</Link>}
            <a className="button-link" href="#features">Explore Features</a>
          </div>
        </section>

        <section id="features" className="landing-section" aria-labelledby="features-title">
          <h2 id="features-title">What JMIP does for you</h2>
          <ul className="landing-features">
            {FEATURES.map(({ title, text, icon: FeatureIcon }) => (
              <li key={title} className="landing-feature">
                <span className="landing-feature-icon" aria-hidden="true"><FeatureIcon size={20} /></span>
                <h3>{title}</h3>
                <p>{text}</p>
              </li>
            ))}
          </ul>
        </section>

        <section className="landing-section" aria-labelledby="workflow-title">
          <h2 id="workflow-title">How it works</h2>
          <ol className="landing-workflow">
            {WORKFLOW.map((item, index) => (
              <li key={item.step}>
                <span className="landing-workflow-number" aria-hidden="true">{index + 1}</span>
                <strong>{item.step}</strong>
                <span>{item.text}</span>
              </li>
            ))}
          </ol>
        </section>

        <section id="privacy" className="landing-section landing-privacy" aria-labelledby="privacy-title">
          <h2 id="privacy-title">How JMIP uses your data</h2>
          <ul>
            <li>Your resume, applications, notes, interviews and learning plan are yours alone; nobody else can see them.</li>
            <li>Resume files and their text are encrypted at rest, and you can delete a resume at any time.</li>
            <li>AI feedback sees only what it needs: the job, your skill names, the question and your answer.</li>
            <li>Your portfolio stays private until you publish it, and only the sections you choose are shown.</li>
          </ul>
        </section>

        <section className="landing-section landing-final" aria-labelledby="final-title">
          <h2 id="final-title">Start with your resume and a goal</h2>
          <p>It takes about three minutes to set up. Every step can be skipped and finished later.</p>
          <Link className="button-link primary landing-cta-main" to={signedIn ? '/' : '/signup'}>
            {signedIn ? 'Go to dashboard' : 'Get Started'}
          </Link>
        </section>
      </main>

      <footer className="app-footer">
        Job data in this deployment is synthetic and for development only. It does not describe the real job market.
      </footer>
    </div>
  );
}
