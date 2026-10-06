import { useEffect, useState } from 'react';
import { useConfirm, useToast } from '../components/feedback';
import { ApiError, api } from '../api/client';
import type {
  CareerGoal,
  Portfolio,
  PortfolioContent,
  PortfolioSections,
  PublicProfile,
  Resume,
} from '../api/types';
import { PageGuide } from '../components/guidance';
import { Badge, Card, ErrorState, PageHeader } from '../components/ui';
import { ProfileView } from '../components/ProfileView';
import { Entries, Entry } from './ResumeBuilder';

type ListKey = 'experience' | 'education' | 'projects' | 'certifications' | 'achievements' | 'links';

const SECTION_LABELS: { key: keyof PortfolioSections; label: string }[] = [
  { key: 'about', label: 'About' },
  { key: 'skills', label: 'Skills' },
  { key: 'experience', label: 'Experience' },
  { key: 'education', label: 'Education' },
  { key: 'projects', label: 'Projects' },
  { key: 'certifications', label: 'Certifications' },
  { key: 'achievements', label: 'Achievements' },
  { key: 'careerGoals', label: 'Career goals' },
  { key: 'links', label: 'Links' },
];

const LISTS: { key: ListKey; label: string }[] = [
  { key: 'experience', label: 'Experience' },
  { key: 'education', label: 'Education' },
  { key: 'projects', label: 'Projects' },
  { key: 'certifications', label: 'Certifications' },
  { key: 'achievements', label: 'Achievements' },
  { key: 'links', label: 'Links' },
];

const DEFAULT_SECTIONS: PortfolioSections = {
  about: true, skills: true, experience: true, education: true, projects: true, certifications: true,
  achievements: true, careerGoals: false, links: true,
};

const EMPTY: PortfolioContent = {
  skills: [], experience: [], education: [], projects: [], certifications: [], achievements: [], links: [],
};

interface Draft {
  displayName: string;
  content: PortfolioContent;
  sections: PortfolioSections;
}

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

const lines = (text: string) => text.split('\n').map((line) => line.trim()).filter(Boolean);

/** The public view of a draft, exactly as the server builds it: hidden and empty sections are left out. */
export function previewOf(draft: Draft, goals: string[]): PublicProfile {
  const { content: c, sections: s } = draft;
  const shown = <T,>(visible: boolean, items: T[]) => (visible && items.length > 0 ? items : undefined);
  return {
    displayName: draft.displayName || 'Your Name',
    headline: c.headline || undefined,
    about: s.about ? c.about || undefined : undefined,
    skills: shown(s.skills, c.skills),
    experience: shown(s.experience, c.experience),
    education: shown(s.education, c.education),
    projects: shown(s.projects, c.projects),
    certifications: shown(s.certifications, c.certifications),
    achievements: shown(s.achievements, c.achievements),
    careerGoals: shown(s.careerGoals, goals),
    links: shown(s.links, c.links),
    updatedAt: '',
  };
}

/** V9.7: build, preview and publish a professional portfolio from your own data. */
export function PortfolioPage() {
  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [resumes, setResumes] = useState<Resume[]>([]);
  const [resumeId, setResumeId] = useState('');
  const [goals, setGoals] = useState<string[]>([]);
  const [learned, setLearned] = useState<string[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [section, setSection] = useState<ListKey>('experience');
  const [slug, setSlug] = useState('');

  useEffect(() => {
    api.portfolio().then(
      (existing) => { adopt(existing); setLoaded(true); },
      (cause: unknown) => {
        if (cause instanceof ApiError && cause.status === 404) setLoaded(true);
        else setLoadError(messageOf(cause));
      });
    api.resumes().then((list) => setResumes(list.filter((resume) => resume.status === 'COMPLETED')), () => setResumes([]));
    api.careerGoals().then((list: CareerGoal[]) =>
      setGoals([...new Set(list.filter((goal) => goal.status === 'ACTIVE').map((goal) => goal.targetRole))]), () => setGoals([]));
  }, []);

  const adopt = (next: Portfolio) => {
    setPortfolio(next);
    setSlug(next.slug);
    setDraft({ displayName: next.displayName, content: { ...EMPTY, ...next.content }, sections: next.sections });
  };

  const act = async (action: () => Promise<void>, done?: string) => {
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      await action();
      if (done) {
        setMessage(done);
        toast(done);
      }
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  };

  const confirmAction = useConfirm();
  const toast = useToast();
  const importResume = () => act(async () => {
    if (draft && portfolio && !(await confirmAction({ title: 'Replace the editor with this resume?', message: 'Nothing is saved until you save.', confirmLabel: 'Replace' }))) return;
    const imported = await api.portfolioImport(resumeId || undefined);
    setDraft({ displayName: imported.displayName, content: { ...EMPTY, ...imported.content },
      sections: draft?.sections ?? DEFAULT_SECTIONS });
    setLearned(imported.learnedSkills);
  }, 'Imported from your resume. Review it, then save.');

  const save = () => act(async () => {
    if (!draft) return;
    adopt(portfolio ? await api.updatePortfolio(draft) : await api.createPortfolio(draft));
  }, 'Portfolio saved.');

  const setContent = (patch: Partial<PortfolioContent>) =>
    setDraft((current) => current && { ...current, content: { ...current.content, ...patch } });

  if (loadError) return <ErrorState message={loadError} />;
  if (!loaded) return <p className="muted small">Loading your portfolio…</p>;

  const publicUrl = portfolio ? `${window.location.origin}${portfolio.publicPath}` : '';

  return (
    <>
      <PageHeader
        title="Professional Portfolio"
        description="Build a profile from your own resume and skills, choose what to show, and publish it when you are ready."
        actions={draft && (
          <button type="button" onClick={save} disabled={busy || !draft.displayName.trim()}>
            {busy ? 'Saving…' : portfolio ? 'Save' : 'Create portfolio'}
          </button>
        )}
      />
      <PageGuide id="portfolio" title="Share your work" helpAnchor="privacy">
        Build a portfolio from your projects and skills. It stays private until you publish it, and you choose which sections are shown.
      </PageGuide>
      {message && <p className="status" role="status">{message}</p>}
      {error && <p className="status status-error" role="alert">{error}</p>}

      <Card title="Start from your data" description="Import sections from one of your resumes. Nothing is saved until you save.">
        <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <label className="field">
            Resume
            <select value={resumeId} onChange={(e) => setResumeId(e.target.value)}>
              <option value="">Your current resume</option>
              {resumes.map((resume) => <option key={resume.id} value={resume.id}>{resume.title ?? resume.fileName}</option>)}
            </select>
          </label>
          <button type="button" className="small" disabled={busy} onClick={importResume}>Import from resume</button>
          {!draft && (
            <button type="button" className="small ghost"
              onClick={() => setDraft({ displayName: '', content: EMPTY, sections: DEFAULT_SECTIONS })}>
              Start blank
            </button>
          )}
        </div>
        {learned.length > 0 && draft && (
          <p className="small">
            Completed in your learning plan:{' '}
            {learned.map((skill) => (
              <button key={skill} type="button" className="small ghost"
                onClick={() => { setContent({ skills: [...draft.content.skills, skill] }); setLearned((all) => all.filter((s) => s !== skill)); }}>
                Add {skill}
              </button>
            ))}
          </p>
        )}
      </Card>

      {portfolio && (
        <Card title="Publishing" description="Only a published profile can be seen, and only its visible sections.">
          <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
            <Badge tone={portfolio.visibility === 'PUBLIC' ? 'success' : 'neutral'}>
              {portfolio.visibility === 'PUBLIC' ? 'Public' : 'Private'}
            </Badge>
            {portfolio.visibility === 'PUBLIC' ? (
              <button type="button" className="small ghost" disabled={busy}
                onClick={() => act(async () => adopt(await api.unpublishPortfolio()), 'Your profile is private again.')}>Unpublish</button>
            ) : (
              <button type="button" className="small" disabled={busy}
                onClick={() => act(async () => adopt(await api.publishPortfolio()), 'Your profile is public.')}>Publish</button>
            )}
          </div>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'flex-end', marginTop: 12 }}>
            <label className="field">
              Profile address
              <input type="text" maxLength={50} value={slug} onChange={(e) => setSlug(e.target.value.toLowerCase())} />
            </label>
            <button type="button" className="small ghost" disabled={busy || slug === portfolio.slug}
              onClick={() => act(async () => adopt(await api.changePortfolioSlug(slug)), 'Profile address changed.')}>
              Change address
            </button>
          </div>
          <p className="small">
            Public URL: <code>{publicUrl}</code>{' '}
            <button type="button" className="small ghost"
              onClick={() => { void navigator.clipboard?.writeText(publicUrl).then(() => setMessage('Link copied.'), () => setError('Copy failed; select the link instead.')); }}>
              Copy link
            </button>
            {portfolio.visibility === 'PUBLIC' && <> <a href={portfolio.publicPath} target="_blank" rel="noopener noreferrer">Open</a></>}
          </p>
        </Card>
      )}

      {draft && (
        <div className="builder-layout">
          <Card title="Profile">
            <div className="alert-form">
              <label className="field">Display name
                <input type="text" maxLength={120} value={draft.displayName} onChange={(e) => setDraft({ ...draft, displayName: e.target.value })} />
              </label>
              <label className="field">Headline
                <input type="text" maxLength={160} value={draft.content.headline ?? ''} onChange={(e) => setContent({ headline: e.target.value })} />
              </label>
              <label className="field alert-form-wide">About
                <textarea rows={4} maxLength={3000} value={draft.content.about ?? ''} onChange={(e) => setContent({ about: e.target.value })} />
              </label>
              <label className="field alert-form-wide">Skills (comma-separated)
                <input type="text" value={draft.content.skills.join(', ')}
                  onChange={(e) => setContent({ skills: e.target.value.split(',').map((s) => s.trim()).filter(Boolean) })} />
              </label>
            </div>

            <fieldset className="builder-entry" style={{ marginTop: 12 }}>
              <legend>Visible on the public profile</legend>
              <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
                {SECTION_LABELS.map(({ key, label }) => (
                  <label key={key} className="small">
                    <input type="checkbox" checked={draft.sections[key]}
                      onChange={(e) => setDraft({ ...draft, sections: { ...draft.sections, [key]: e.target.checked } })} /> {label}
                  </label>
                ))}
              </div>
            </fieldset>

            <nav className="row" aria-label="Portfolio sections" style={{ gap: 4, flexWrap: 'wrap', margin: '12px 0' }}>
              {LISTS.map((item) => (
                <button key={item.key} type="button" className={section === item.key ? 'small' : 'small ghost'}
                  aria-pressed={section === item.key} onClick={() => setSection(item.key)}>{item.label}</button>
              ))}
            </nav>
            <ListEditor section={section} content={draft.content} onChange={setContent} />
          </Card>
          <Card title="Live preview" description="Exactly what a visitor sees once published.">
            <ProfileView profile={previewOf(draft, goals)} />
          </Card>
        </div>
      )}
    </>
  );
}

function ListEditor({ section, content, onChange }: {
  section: ListKey; content: PortfolioContent; onChange: (patch: Partial<PortfolioContent>) => void;
}) {
  const update = <K extends ListKey>(key: K, index: number, patch: Partial<PortfolioContent[K][number]>) =>
    onChange({ [key]: content[key].map((item, i) => (i === index ? { ...(item as object), ...patch } : item)) } as Partial<PortfolioContent>);
  const remove = (key: ListKey, index: number) =>
    onChange({ [key]: content[key].filter((_, i) => i !== index) } as Partial<PortfolioContent>);
  const add = <K extends ListKey>(key: K, item: PortfolioContent[K][number]) =>
    onChange({ [key]: [...content[key], item] } as Partial<PortfolioContent>);

  switch (section) {
    case 'experience':
      return (
        <Entries label="experience" onAdd={() => add('experience', { title: '', company: '', current: false, bullets: [] })}>
          {content.experience.map((job, i) => (
            <Entry key={i} label={`Experience ${i + 1}`} onRemove={() => remove('experience', i)}>
              <label className="field">Title<input type="text" maxLength={120} value={job.title} onChange={(e) => update('experience', i, { title: e.target.value })} /></label>
              <label className="field">Company<input type="text" maxLength={160} value={job.company} onChange={(e) => update('experience', i, { company: e.target.value })} /></label>
              <label className="field">Start<input type="text" maxLength={20} value={job.start ?? ''} onChange={(e) => update('experience', i, { start: e.target.value })} /></label>
              <label className="field">End<input type="text" maxLength={20} value={job.end ?? ''} disabled={job.current} onChange={(e) => update('experience', i, { end: e.target.value })} /></label>
              <label className="small"><input type="checkbox" checked={job.current} onChange={(e) => update('experience', i, { current: e.target.checked })} /> Current role</label>
              <label className="field alert-form-wide">Highlights (one per line)
                <textarea rows={3} value={(job.bullets ?? []).join('\n')} onChange={(e) => update('experience', i, { bullets: lines(e.target.value) })} />
              </label>
            </Entry>
          ))}
        </Entries>
      );
    case 'education':
      return (
        <Entries label="education" onAdd={() => add('education', { degree: '', institution: '' })}>
          {content.education.map((school, i) => (
            <Entry key={i} label={`Education ${i + 1}`} onRemove={() => remove('education', i)}>
              <label className="field">Degree<input type="text" maxLength={160} value={school.degree} onChange={(e) => update('education', i, { degree: e.target.value })} /></label>
              <label className="field">Institution<input type="text" maxLength={160} value={school.institution} onChange={(e) => update('education', i, { institution: e.target.value })} /></label>
              <label className="field">Start<input type="text" maxLength={20} value={school.start ?? ''} onChange={(e) => update('education', i, { start: e.target.value })} /></label>
              <label className="field">End<input type="text" maxLength={20} value={school.end ?? ''} onChange={(e) => update('education', i, { end: e.target.value })} /></label>
            </Entry>
          ))}
        </Entries>
      );
    case 'projects':
      return (
        <Entries label="project" onAdd={() => add('projects', { name: '' })}>
          {content.projects.map((project, i) => (
            <Entry key={i} label={`Project ${i + 1}`} onRemove={() => remove('projects', i)}>
              <label className="field">Name<input type="text" maxLength={120} value={project.name} onChange={(e) => update('projects', i, { name: e.target.value })} /></label>
              <label className="field">Link<input type="url" maxLength={200} value={project.url ?? ''} placeholder="https://" onChange={(e) => update('projects', i, { url: e.target.value })} /></label>
              <label className="field alert-form-wide">Description
                <textarea rows={2} maxLength={800} value={project.description ?? ''} onChange={(e) => update('projects', i, { description: e.target.value })} />
              </label>
            </Entry>
          ))}
        </Entries>
      );
    case 'certifications':
      return (
        <Entries label="certification" onAdd={() => add('certifications', { name: '' })}>
          {content.certifications.map((cert, i) => (
            <Entry key={i} label={`Certification ${i + 1}`} onRemove={() => remove('certifications', i)}>
              <label className="field">Name<input type="text" maxLength={160} value={cert.name} onChange={(e) => update('certifications', i, { name: e.target.value })} /></label>
              <label className="field">Issuer<input type="text" maxLength={160} value={cert.issuer ?? ''} onChange={(e) => update('certifications', i, { issuer: e.target.value })} /></label>
              <label className="field">Date<input type="text" maxLength={20} value={cert.date ?? ''} onChange={(e) => update('certifications', i, { date: e.target.value })} /></label>
            </Entry>
          ))}
        </Entries>
      );
    case 'achievements':
      return (
        <label className="field">Achievements (one per line)
          <textarea rows={5} value={content.achievements.join('\n')} onChange={(e) => onChange({ achievements: lines(e.target.value) })} />
        </label>
      );
    default:
      return (
        <Entries label="link" onAdd={() => add('links', { label: '', url: '' })}>
          {content.links.map((link, i) => (
            <Entry key={i} label={`Link ${i + 1}`} onRemove={() => remove('links', i)}>
              <label className="field">Label<input type="text" maxLength={60} value={link.label} onChange={(e) => update('links', i, { label: e.target.value })} /></label>
              <label className="field">Address<input type="url" maxLength={300} value={link.url} placeholder="https://" onChange={(e) => update('links', i, { url: e.target.value })} /></label>
            </Entry>
          ))}
        </Entries>
      );
  }
}
