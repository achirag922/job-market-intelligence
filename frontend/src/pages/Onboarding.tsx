import { useEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type { CategoryDemand, OnboardingStatus, Resume } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useToast } from '../components/feedback';
import { Card, ErrorState, PageHeader, SkeletonCards } from '../components/ui';

type Step = 'welcome' | 'profile' | 'resume' | 'preferences' | 'goal' | 'done';

const TRACKED: { step: Exclude<Step, 'welcome' | 'done'>; label: string; done: (s: OnboardingStatus) => boolean }[] = [
  { step: 'profile', label: 'Profile', done: (s) => s.steps.profile },
  { step: 'resume', label: 'Resume', done: (s) => s.steps.resume },
  { step: 'preferences', label: 'Preferences', done: (s) => s.steps.preferences },
  { step: 'goal', label: 'Career Goal', done: (s) => s.steps.careerGoal },
];

const ORDER: Step[] = ['welcome', 'profile', 'resume', 'preferences', 'goal', 'done'];
const CURRENCIES = ['EUR', 'USD', 'GBP', 'INR', 'CAD', 'AUD'];

function stepFor(status: OnboardingStatus): Step {
  if (status.completedSteps === 0 && status.status === 'PENDING') return 'welcome';
  // V9.15: after onboarding this page is where the profile and preferences are edited.
  if (status.status === 'COMPLETED') return 'profile';
  return ({ PROFILE: 'profile', RESUME: 'resume', PREFERENCES: 'preferences', CAREER_GOAL: 'goal', DONE: 'done' } as const)[status.nextStep];
}

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

/** The goal's experience band from years of experience, as the career-goal form offers it. */
export function experienceBand(years?: number): string {
  if (years === undefined) return '';
  return years < 2 ? '0-2' : years < 5 ? '2-5' : years < 8 ? '5-8' : '8+';
}

const splitList = (text: string) => [...new Set(text.split(',').map((part) => part.trim()).filter(Boolean))];

/**
 * V9.12: first-time onboarding. Each answer is saved where JMIP already keeps it (match preferences,
 * resumes, career goals), so recommendations, skill gaps and the dashboard use it straight away.
 * Every step can be skipped, and so can the whole flow; it can be finished later from the dashboard.
 */
export function Onboarding() {
  const [status, setStatus] = useState<OnboardingStatus | null>(null);
  const [step, setStep] = useState<Step>('welcome');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [leaving, setLeaving] = useState(false);
  const heading = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const toast = useToast();

  useEffect(() => {
    setLoadError(null);
    api.onboarding().then((next) => {
      setStatus(next);
      setStep(stepFor(next));
    }, (cause: unknown) => setLoadError(messageOf(cause)));
  }, [attempt]);

  // Moving between steps puts keyboard and screen-reader focus on the new step.
  useEffect(() => {
    heading.current?.focus();
  }, [step]);

  const go = (next: Step) => setStep(next);
  const forward = () => go(ORDER[Math.min(ORDER.indexOf(step) + 1, ORDER.length - 1)]);
  const back = () => go(ORDER[Math.max(ORDER.indexOf(step) - 1, 0)]);
  const refresh = async () => setStatus(await api.onboarding());

  const skipAll = async () => {
    setLeaving(true);
    try {
      await api.skipOnboarding();
      toast('You can finish setting up any time from the dashboard.', 'info');
      navigate('/');
    } catch (cause) {
      toast(messageOf(cause), 'error');
      setLeaving(false);
    }
  };

  if (loadError) {
    return <ErrorState message={loadError} onRetry={() => setAttempt((count) => count + 1)} />;
  }
  if (!status) {
    return <SkeletonCards count={2} />;
  }

  return (
    <div className="onboarding">
      <PageHeader
        title={status.status === 'COMPLETED' ? 'Profile & Preferences' : 'Set up JMIP'}
        description={status.status === 'COMPLETED'
          ? 'Change your career profile, resume, job preferences or goal; recommendations and matches update with them.'
          : 'A few answers personalize your job matches, skill gaps and dashboard. Everything can be changed later.'}
        actions={step !== 'done' && (
          <button type="button" className="ghost small" disabled={leaving} onClick={skipAll}>
            {leaving ? 'Leaving…' : 'Skip for now'}
          </button>
        )}
      />

      <ol className="onboarding-progress" aria-label="Onboarding progress">
        {TRACKED.map((item) => {
          const done = item.done(status);
          return (
            <li key={item.step} className={done ? 'is-done' : undefined} aria-current={step === item.step ? 'step' : undefined}>
              {item.label}
              {done && <span className="onboarding-check" aria-label="done">✓</span>}
            </li>
          );
        })}
      </ol>

      <div ref={heading} tabIndex={-1} className="onboarding-step">
        {step === 'welcome' && <Welcome onStart={forward} />}
        {step === 'profile' && (
          <ProfileStep status={status} onBack={back} onSkip={forward}
            onSaved={(next) => { setStatus(next); toast('Profile saved.'); forward(); }} />
        )}
        {step === 'resume' && <ResumeStep status={status} onBack={back} onNext={forward} onChanged={refresh} />}
        {step === 'preferences' && (
          <PreferencesStep status={status} onBack={back} onSkip={forward}
            onSaved={(next) => { setStatus(next); toast('Job preferences saved.'); forward(); }} />
        )}
        {step === 'goal' && (
          <GoalStep status={status} onBack={back} onNext={forward}
            onCreated={async () => { await refresh(); toast('Career goal created.'); forward(); }} />
        )}
        {step === 'done' && <Done status={status} onBack={back} />}
      </div>
    </div>
  );
}

function Nav({ onBack, children }: { onBack?: () => void; children: ReactNode }) {
  return (
    <div className="onboarding-nav">
      {onBack ? <button type="button" className="ghost" onClick={onBack}>Back</button> : <span />}
      <div className="onboarding-nav-end">{children}</div>
    </div>
  );
}

function Welcome({ onStart }: { onStart: () => void }) {
  const { user } = useAuth();
  return (
    <Card title={`Welcome${user?.fullName ? `, ${user.fullName.split(' ')[0]}` : ''}!`}
      description="Your email is verified. Four short steps tailor JMIP to you: your career profile, your resume, your job preferences and a career goal.">
      <p className="small">It takes about three minutes. You can skip any step, or all of them, and come back later.</p>
      <Nav>
        <button type="button" onClick={onStart}>Get started</button>
      </Nav>
    </Card>
  );
}

function ProfileStep({ status, onBack, onSkip, onSaved }: {
  status: OnboardingStatus; onBack: () => void; onSkip: () => void; onSaved: (next: OnboardingStatus) => void;
}) {
  const [targetRole, setTargetRole] = useState(status.profile.targetRole ?? '');
  const [years, setYears] = useState(status.profile.yearsExperience?.toString() ?? '');
  const [skills, setSkills] = useState((status.profile.skills ?? []).join(', '));
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const yearsNumber = Number(years);
  const problems = {
    targetRole: targetRole.trim() ? null : 'Enter the role you are aiming for.',
    years: years.trim() === '' ? 'Enter your years of experience.'
      : !Number.isInteger(yearsNumber) || yearsNumber < 0 || yearsNumber > 60 ? 'Use a whole number from 0 to 60.' : null,
    skills: splitList(skills).length === 0 ? 'Add at least one skill or interest.'
      : splitList(skills).length > 20 ? 'Add at most 20 skills.' : null,
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    if (problems.targetRole || problems.years || problems.skills) return;
    setBusy(true);
    setError(null);
    try {
      onSaved(await api.saveOnboardingProfile({ targetRole: targetRole.trim(), yearsExperience: yearsNumber, skills: splitList(skills) }));
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  };

  const fieldError = (id: string, problem: string | null) =>
    submitted && problem ? <span id={id} className="onboarding-field-error">{problem}</span> : null;

  return (
    <Card title="Your career profile" description="What you are aiming for and what you bring. Used to rank jobs and suggest skills.">
      <form className="alert-form" onSubmit={submit} noValidate aria-label="Career profile">
        <div className="field">
          <label htmlFor="ob-role">Target job or role</label>
          <input id="ob-role" type="text" maxLength={100} value={targetRole} placeholder="e.g. Backend Engineer" disabled={busy}
            onChange={(e) => setTargetRole(e.target.value)} aria-invalid={Boolean(submitted && problems.targetRole) || undefined}
            aria-describedby={submitted && problems.targetRole ? 'ob-role-error' : undefined} />
          {fieldError('ob-role-error', problems.targetRole)}
        </div>
        <div className="field">
          <label htmlFor="ob-years">Years of experience</label>
          <input id="ob-years" type="number" min={0} max={60} value={years} disabled={busy} onChange={(e) => setYears(e.target.value)}
            aria-invalid={Boolean(submitted && problems.years) || undefined}
            aria-describedby={submitted && problems.years ? 'ob-years-error' : undefined} />
          {fieldError('ob-years-error', problems.years)}
        </div>
        <div className="field alert-form-wide">
          <label htmlFor="ob-skills">Key skills and interests (comma-separated)</label>
          <input id="ob-skills" type="text" value={skills} placeholder="e.g. Java, Spring Boot, Cloud" disabled={busy}
            onChange={(e) => setSkills(e.target.value)} aria-invalid={Boolean(submitted && problems.skills) || undefined}
            aria-describedby={submitted && problems.skills ? 'ob-skills-error' : undefined} />
          {fieldError('ob-skills-error', problems.skills)}
        </div>
        {error && <p className="status status-error alert-form-wide" role="alert">{error}</p>}
        <div className="alert-form-wide">
          <Nav onBack={onBack}>
            <button type="button" className="ghost" onClick={onSkip}>Skip this step</button>
            <button type="submit" disabled={busy} aria-busy={busy || undefined}>{busy ? 'Saving…' : 'Save and continue'}</button>
          </Nav>
        </div>
      </form>
    </Card>
  );
}

function ResumeStep({ status, onBack, onNext, onChanged }: {
  status: OnboardingStatus; onBack: () => void; onNext: () => void; onChanged: () => Promise<void>;
}) {
  const [resumes, setResumes] = useState<Resume[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<Resume | null>(null);
  const toast = useToast();

  const load = () => api.resumes().then((list) => setResumes(list.filter((resume) => resume.status === 'COMPLETED')),
    (cause: unknown) => setError(messageOf(cause)));
  useEffect(() => {
    void load();
  }, []);

  const act = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      await load();
      await onChanged();
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  };

  const upload = (file: File | undefined) => file && act(async () => {
    const uploaded = await api.uploadResume(file);
    setResult(uploaded);
    if (uploaded.status === 'COMPLETED') toast(`Resume read: ${uploaded.skills.length} skills found.`);
  });

  return (
    <Card title="Your resume" description="Upload a PDF or pick one you already have. JMIP reads its skills to match you with jobs; the file stays private.">
      {resumes === null ? (
        <p className="muted small">Loading your resumes…</p>
      ) : resumes.length > 0 && (
        <fieldset className="builder-entry">
          <legend>Use a resume you already have</legend>
          <div className="stack" style={{ gap: 6 }}>
            {resumes.map((resume) => (
              <label key={resume.id} className="row small" style={{ gap: 8 }}>
                <input type="radio" name="onboarding-resume" checked={Boolean(resume.isDefault)} disabled={busy}
                  onChange={() => act(async () => { await api.setDefaultResume(resume.id); })} />
                {resume.title ?? resume.fileName} · {resume.skills.length} skills
              </label>
            ))}
          </div>
        </fieldset>
      )}
      <label className="field" style={{ marginTop: 12 }}>
        Upload a resume (PDF)
        <input type="file" accept="application/pdf,.pdf" disabled={busy} onChange={(e) => upload(e.target.files?.[0])} />
      </label>
      {busy && <p className="muted small" role="status">Reading your resume…</p>}
      {result?.status === 'COMPLETED' && (
        <p className="small" role="status">
          Skills found: {result.skills.length > 0 ? result.skills.map((skill) => skill.name).join(', ') : 'none we recognise yet'}.
        </p>
      )}
      {result?.status === 'FAILED' && (
        <p className="status status-error" role="alert">{result.errorMessage ?? 'This resume could not be read. Try another PDF.'}</p>
      )}
      {error && <p className="status status-error" role="alert">{error}</p>}
      <Nav onBack={onBack}>
        {!status.steps.resume && <button type="button" className="ghost" onClick={onNext}>Skip this step</button>}
        <button type="button" disabled={busy || !status.steps.resume} onClick={onNext}>Continue</button>
      </Nav>
    </Card>
  );
}

function PreferencesStep({ status, onBack, onSkip, onSaved }: {
  status: OnboardingStatus; onBack: () => void; onSkip: () => void; onSaved: (next: OnboardingStatus) => void;
}) {
  const saved = status.preferences;
  const [location, setLocation] = useState(saved.preferredLocation ?? '');
  const [workMode, setWorkMode] = useState<string>(saved.workMode ?? '');
  const [minSalary, setMinSalary] = useState(saved.minSalary?.toString() ?? '');
  const [currency, setCurrency] = useState(saved.salaryCurrency ?? 'EUR');
  const [categories, setCategories] = useState<string[]>(saved.preferredCategories ?? []);
  const [options, setOptions] = useState<CategoryDemand[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.jobCategories().then(setOptions, () => setOptions([]));
  }, []);

  const toggle = (category: string) =>
    setCategories((all) => (all.includes(category) ? all.filter((c) => c !== category) : [...all, category]));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const salary = minSalary.trim() === '' ? undefined : Number(minSalary);
    if (salary !== undefined && (!Number.isFinite(salary) || salary < 0)) {
      setError('Enter a minimum salary of 0 or more, or leave it empty.');
      return;
    }
    if (!location.trim() && !workMode && salary === undefined && categories.length === 0) {
      setError('Choose at least one preference, or skip this step.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      onSaved(await api.saveOnboardingPreferences({
        preferredLocation: location.trim() || undefined,
        workMode: (workMode || undefined) as 'REMOTE' | 'HYBRID' | 'ON_SITE' | undefined,
        minSalary: salary,
        salaryCurrency: salary === undefined ? undefined : currency,
        preferredCategories: categories,
      }));
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Job preferences" description="Where and how you want to work. Used to rank and filter recommendations.">
      <form className="alert-form" onSubmit={submit} noValidate aria-label="Job preferences">
        <label className="field">
          Preferred location
          <input type="text" maxLength={200} value={location} placeholder="e.g. Berlin" disabled={busy}
            onChange={(e) => setLocation(e.target.value)} />
        </label>
        <label className="field">
          Work mode
          <select value={workMode} disabled={busy} onChange={(e) => setWorkMode(e.target.value)}>
            <option value="">No preference</option>
            <option value="REMOTE">Remote</option>
            <option value="HYBRID">Hybrid</option>
            <option value="ON_SITE">On-site</option>
          </select>
        </label>
        <label className="field">
          Minimum salary (yearly)
          <input type="number" min={0} value={minSalary} disabled={busy} onChange={(e) => setMinSalary(e.target.value)} />
        </label>
        <label className="field">
          Currency
          <select value={currency} disabled={busy || minSalary.trim() === ''} onChange={(e) => setCurrency(e.target.value)}>
            {CURRENCIES.map((code) => <option key={code} value={code}>{code}</option>)}
          </select>
        </label>
        {options.length > 0 && (
          <fieldset className="builder-entry alert-form-wide">
            <legend>Preferred job categories</legend>
            <div className="onboarding-choices">
              {options.map((option) => (
                <label key={option.category} className="small">
                  <input type="checkbox" checked={categories.includes(option.category)} disabled={busy}
                    onChange={() => toggle(option.category)} /> {option.category}
                </label>
              ))}
            </div>
          </fieldset>
        )}
        {error && <p className="status status-error alert-form-wide" role="alert">{error}</p>}
        <div className="alert-form-wide">
          <Nav onBack={onBack}>
            <button type="button" className="ghost" onClick={onSkip}>Skip this step</button>
            <button type="submit" disabled={busy} aria-busy={busy || undefined}>{busy ? 'Saving…' : 'Save and continue'}</button>
          </Nav>
        </div>
      </form>
    </Card>
  );
}

function GoalStep({ status, onBack, onNext, onCreated }: {
  status: OnboardingStatus; onBack: () => void; onNext: () => void; onCreated: () => Promise<void>;
}) {
  const [targetRole, setTargetRole] = useState(status.profile.targetRole ?? '');
  const [category, setCategory] = useState(status.preferences.preferredCategories?.[0] ?? '');
  const [experience, setExperience] = useState(experienceBand(status.profile.yearsExperience));
  const [location, setLocation] = useState(status.preferences.preferredLocation ?? '');
  const [options, setOptions] = useState<CategoryDemand[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.jobCategories().then(setOptions, () => setOptions([]));
  }, []);

  if (status.steps.careerGoal) {
    return (
      <Card title="Career goal" description="You already have an active career goal, so your skill roadmap is ready.">
        <p className="small"><Link to="/career-goals">View or change your goals</Link></p>
        <Nav onBack={onBack}><button type="button" onClick={onNext}>Continue</button></Nav>
      </Card>
    );
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!targetRole.trim() || !category) {
      setError(!targetRole.trim() ? 'Enter the role you are working towards.' : 'Choose the job category closest to that role.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.createCareerGoal({ targetRole: targetRole.trim(), targetCategory: category,
        targetExperience: experience || undefined, targetLocation: location.trim() || undefined, targetSkills: [] });
      await onCreated();
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Career goal" description="The role you are working towards. JMIP builds a skill roadmap from real job postings in its category.">
      <form className="alert-form" onSubmit={submit} noValidate aria-label="Career goal">
        <label className="field">
          Target role
          <input type="text" maxLength={100} value={targetRole} disabled={busy} onChange={(e) => setTargetRole(e.target.value)} />
        </label>
        <label className="field">
          Job category
          <select value={category} disabled={busy} onChange={(e) => setCategory(e.target.value)}>
            <option value="">Choose a category</option>
            {options.map((option) => <option key={option.category} value={option.category}>{option.category}</option>)}
          </select>
        </label>
        <label className="field">
          Experience
          <select value={experience} disabled={busy} onChange={(e) => setExperience(e.target.value)}>
            <option value="">Any</option>
            <option value="0-2">0–2 years</option>
            <option value="2-5">2–5 years</option>
            <option value="5-8">5–8 years</option>
            <option value="8+">8+ years</option>
          </select>
        </label>
        <label className="field">
          Location (optional)
          <input type="text" maxLength={120} value={location} disabled={busy} onChange={(e) => setLocation(e.target.value)} />
        </label>
        {error && <p className="status status-error alert-form-wide" role="alert">{error}</p>}
        <div className="alert-form-wide">
          <Nav onBack={onBack}>
            <button type="button" className="ghost" onClick={onNext}>Skip this step</button>
            <button type="submit" disabled={busy} aria-busy={busy || undefined}>{busy ? 'Creating…' : 'Create goal and continue'}</button>
          </Nav>
        </div>
      </form>
    </Card>
  );
}

function Done({ status, onBack }: { status: OnboardingStatus; onBack: () => void }) {
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const toast = useToast();
  const finish = async () => {
    setBusy(true);
    try {
      await api.completeOnboarding();
      toast("You're all set. Your dashboard is now personalized.");
      navigate('/my-career');
    } catch (cause) {
      toast(messageOf(cause), 'error');
      setBusy(false);
    }
  };
  const phrase: Record<string, string> = {
    profile: 'your career profile', resume: 'your resume', preferences: 'your job preferences', goal: 'a career goal',
  };
  const remaining = TRACKED.filter((item) => !item.done(status)).map((item) => phrase[item.step]);
  return (
    <Card title={remaining.length === 0 ? 'All set!' : 'Almost there'}
      description={remaining.length === 0
        ? 'Your matches, skill gaps and dashboard now use your profile, resume, preferences and goal.'
        : `You can add ${remaining.join(' and ')} later from the dashboard; JMIP already uses what you have given it.`}>
      <ul className="small">
        <li><Link to="/for-you">Jobs picked for you</Link></li>
        <li><Link to="/career-goals">Your skill roadmap and gaps</Link></li>
        <li><Link to="/resume">Resume intelligence</Link></li>
      </ul>
      <Nav onBack={onBack}>
        <button type="button" disabled={busy} aria-busy={busy || undefined} onClick={finish}>
          {busy ? 'Finishing…' : 'Go to my dashboard'}
        </button>
      </Nav>
    </Card>
  );
}
