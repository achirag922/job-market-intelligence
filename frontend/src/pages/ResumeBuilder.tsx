import { useEffect, useMemo, useState } from 'react';
import { useConfirm, useToast } from '../components/feedback';
import { ApiError, api } from '../api/client';
import type { BuilderContent, Resume, SavedJob } from '../api/types';
import { ResumeOptimizer } from '../components/ResumeOptimizer';
import { ResumePreview } from '../components/ResumePreview';
import { Badge, Card, EmptyState, PageHeader } from '../components/ui';

type SectionKey = 'personal' | 'summary' | 'skills' | 'experience' | 'education' | 'projects' | 'certifications'
  | 'achievements' | 'additional';

const SECTIONS: { key: SectionKey; label: string }[] = [
  { key: 'personal', label: 'Personal' },
  { key: 'summary', label: 'Summary' },
  { key: 'skills', label: 'Skills' },
  { key: 'experience', label: 'Experience' },
  { key: 'education', label: 'Education' },
  { key: 'projects', label: 'Projects' },
  { key: 'certifications', label: 'Certifications' },
  { key: 'achievements', label: 'Achievements' },
  { key: 'additional', label: 'Additional' },
];

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

/** What the server would refuse, checked before saving so the user sees it next to the section. */
export function validate(content: BuilderContent): string[] {
  const errors: string[] = [];
  if (!content.personal.fullName?.trim()) errors.push('Personal: full name is required.');
  if (content.personal.email && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(content.personal.email)) errors.push('Personal: the email address is not valid.');
  (content.experience ?? []).forEach((job, i) => {
    if (!job.title?.trim() || !job.company?.trim()) errors.push(`Experience ${i + 1}: job title and company are required.`);
  });
  (content.education ?? []).forEach((school, i) => {
    if (!school.degree?.trim() || !school.institution?.trim()) errors.push(`Education ${i + 1}: degree and institution are required.`);
  });
  (content.projects ?? []).forEach((project, i) => { if (!project.name?.trim()) errors.push(`Projects ${i + 1}: a name is required.`); });
  (content.certifications ?? []).forEach((cert, i) => { if (!cert.name?.trim()) errors.push(`Certifications ${i + 1}: a name is required.`); });
  (content.additional ?? []).forEach((extra, i) => { if (!extra.title?.trim()) errors.push(`Additional ${i + 1}: a section title is required.`); });
  return errors;
}

const lines = (text: string) => text.split('\n').map((line) => line.trim()).filter(Boolean);

/**
 * V9.4: write resumes inside JMIP. Resume list → create/edit → sections → preview → export. Built
 * resumes are ordinary resumes: they can be matched, optimised and used everywhere an upload can.
 */
export function ResumeBuilder() {
  const confirmAction = useConfirm();
  const toast = useToast();
  const [resumes, setResumes] = useState<Resume[] | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => api.resumes().then((all) => setResumes(all.filter((resume) => resume.source === 'BUILDER')),
    (cause: unknown) => setError(messageOf(cause)));
  useEffect(() => { void load(); }, []);

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try { await action(); await load(); } catch (cause) { setError(messageOf(cause)); } finally { setBusy(false); }
  };

  if (editing) {
    return <ResumeEditor id={editing} onClose={() => { setEditing(null); void load(); }} />;
  }

  return (
    <>
      <PageHeader title="Resume Builder" description="Write your resume section by section, preview it in a clean template and export it as a PDF. Uploaded resumes stay on Resume Intelligence." />
      <Card title="New resume">
        <form className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}
          onSubmit={(event) => { event.preventDefault(); void run(async () => {
            const created = await api.createBuiltResume({ title: title.trim() || undefined });
            setTitle('');
            setEditing(created.resume.id);
          }); }}>
          <label className="field" style={{ flex: '1 1 240px' }}>
            Name
            <input type="text" maxLength={100} value={title} onChange={(e) => setTitle(e.target.value)} placeholder="e.g. Backend roles" />
          </label>
          <button type="submit" disabled={busy}>Create resume</button>
        </form>
      </Card>
      <Card title="Your built resumes">
        {error && <p className="status status-error" role="alert">{error}</p>}
        {resumes === null ? <p className="muted small">Loading…</p> : resumes.length === 0 ? (
          <EmptyState title="No built resumes yet" message="Create one above; you can keep several versions for different roles." />
        ) : (
          <ul className="alert-list">
            {resumes.map((resume) => (
              <li key={resume.id} className="alert-item">
                <div className="alert-item-main">
                  <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                    <strong>{resume.title}</strong>
                    {resume.versionLabel && <span className="muted">{resume.versionLabel}</span>}
                    {resume.isDefault && <Badge tone="brand">Default</Badge>}
                  </div>
                  <p className="muted small" style={{ margin: '4px 0 0' }}>
                    Updated {resume.updatedAt ? new Date(resume.updatedAt).toLocaleString() : '—'} · {resume.skills.length} skill(s) recognised
                  </p>
                </div>
                <div className="alert-item-actions">
                  <button type="button" className="small" onClick={() => setEditing(resume.id)}>Edit {resume.title}</button>
                  <button type="button" className="small ghost" disabled={busy} onClick={() => run(async () => { await api.duplicateResume(resume.id); })}>Duplicate</button>
                  {!resume.isDefault && (
                    <button type="button" className="small ghost" disabled={busy} onClick={() => run(async () => { await api.setDefaultResume(resume.id); })}>Set default</button>
                  )}
                  <button type="button" className="small ghost" disabled={busy} onClick={() => run(() => downloadPdf(resume.id))}>Download PDF</button>
                  <button type="button" className="small ghost" disabled={busy} onClick={async () => {
                    if (await confirmAction({ title: `Delete “${resume.title}”?`, message: 'This cannot be undone.', confirmLabel: 'Delete', tone: 'danger' })) {
                      void run(async () => { await api.deleteResume(resume.id); toast('Resume deleted.'); });
                    }
                  }}>Delete</button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}

async function downloadPdf(id: string) {
  const { blob, fileName } = await api.downloadResumePdf(id);
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

function ResumeEditor({ id, onClose }: { id: string; onClose: () => void }) {
  const confirmAction = useConfirm();
  const [resume, setResume] = useState<Resume | null>(null);
  const [draft, setDraft] = useState<BuilderContent | null>(null);
  const [saved, setSaved] = useState('');
  const [section, setSection] = useState<SectionKey>('personal');
  const [title, setTitle] = useState('');
  const [label, setLabel] = useState('');
  const [errors, setErrors] = useState<string[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [jobs, setJobs] = useState<SavedJob[]>([]);
  const [jobId, setJobId] = useState('');

  useEffect(() => {
    api.builtResume(id).then((result) => {
      setResume(result.resume);
      setDraft(result.content);
      setSaved(JSON.stringify(result.content));
      setTitle(result.resume.title ?? '');
      setLabel(result.resume.versionLabel ?? '');
    }, (cause: unknown) => setMessage(messageOf(cause)));
    api.savedJobs().then(setJobs, () => setJobs([]));
  }, [id]);

  const dirty = useMemo(() => draft !== null && JSON.stringify(draft) !== saved, [draft, saved]);
  if (!draft || !resume) {
    return <p className="muted small">{message ?? 'Loading…'}</p>;
  }

  const set = (patch: Partial<BuilderContent>) => setDraft({ ...draft, ...patch });
  const setPersonal = (patch: Partial<BuilderContent['personal']>) => set({ personal: { ...draft.personal, ...patch } });
  function listOf<K extends 'experience' | 'education' | 'projects' | 'certifications' | 'additional'>(key: K) {
    const items = (draft![key] ?? []) as NonNullable<BuilderContent[K]>;
    return {
      items,
      update: (index: number, patch: object) => set({ [key]: items.map((item, i) => (i === index ? { ...item, ...patch } : item)) } as Partial<BuilderContent>),
      add: (blank: object) => set({ [key]: [...items, blank] } as Partial<BuilderContent>),
      remove: (index: number) => set({ [key]: items.filter((_, i) => i !== index) } as Partial<BuilderContent>),
    };
  }

  const save = async () => {
    const problems = validate(draft);
    setErrors(problems);
    setMessage(null);
    if (problems.length > 0) return;
    setBusy(true);
    try {
      const result = await api.saveBuiltResume(id, draft);
      setDraft(result.content);
      setSaved(JSON.stringify(result.content));
      setResume(result.resume);
      if (title.trim() && (title.trim() !== result.resume.title || label.trim() !== (result.resume.versionLabel ?? ''))) {
        setResume(await api.updateResume(id, title.trim(), label.trim()));
      }
      setMessage('Saved.');
    } catch (cause) {
      setMessage(messageOf(cause));
    } finally {
      setBusy(false);
    }
  };

  const experience = listOf('experience');
  const education = listOf('education');
  const projects = listOf('projects');
  const certifications = listOf('certifications');
  const additional = listOf('additional');

  return (
    <>
      <PageHeader
        title={`Edit: ${resume.title}`}
        description="Only what you write appears on your resume. JMIP never adds skills or experience for you."
        actions={
          <div className="row" style={{ gap: 8 }}>
            <span className={dirty ? 'status status-error small' : 'muted small'} role="status">{dirty ? 'Unsaved changes' : 'All changes saved'}</span>
            <button type="button" onClick={save} disabled={busy || !dirty}>{busy ? 'Saving…' : 'Save'}</button>
            <button type="button" className="ghost" disabled={dirty} title={dirty ? 'Save first' : undefined}
              onClick={() => { void downloadPdf(id).catch((cause) => setMessage(messageOf(cause))); }}>Export PDF</button>
            <button type="button" className="ghost" onClick={async () => {
              if (!dirty || await confirmAction({ title: 'Leave without saving?', message: 'Your unsaved changes will be lost.', confirmLabel: 'Leave', tone: 'danger' })) onClose();
            }}>Back to list</button>
          </div>
        }
      />
      {message && <p className="status" role="status">{message}</p>}
      {errors.length > 0 && (
        <ul className="status status-error" role="alert">{errors.map((problem) => <li key={problem}>{problem}</li>)}</ul>
      )}
      <div className="builder-layout">
        <Card title="Sections">
          <div className="row" style={{ gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
            <label className="field">Name<input type="text" maxLength={100} value={title} onChange={(e) => setTitle(e.target.value)} /></label>
            <label className="field">Version label<input type="text" maxLength={50} value={label} onChange={(e) => setLabel(e.target.value)} /></label>
            <label className="field">
              Template
              <select value={draft.template ?? 'CLASSIC'} onChange={(e) => set({ template: e.target.value as BuilderContent['template'] })}>
                <option value="CLASSIC">Classic (serif)</option>
                <option value="MODERN">Modern (sans-serif)</option>
              </select>
            </label>
          </div>
          <nav className="row" aria-label="Resume sections" style={{ gap: 4, flexWrap: 'wrap', marginBottom: 12 }}>
            {SECTIONS.map((item) => (
              <button key={item.key} type="button" className={section === item.key ? 'small' : 'small ghost'}
                aria-pressed={section === item.key} onClick={() => setSection(item.key)}>{item.label}</button>
            ))}
          </nav>

          {section === 'personal' && (
            <div className="alert-form">
              <label className="field">Full name<input type="text" maxLength={120} value={draft.personal.fullName} onChange={(e) => setPersonal({ fullName: e.target.value })} /></label>
              <label className="field">Headline<input type="text" maxLength={160} value={draft.personal.headline ?? ''} onChange={(e) => setPersonal({ headline: e.target.value })} /></label>
              <label className="field">Email<input type="email" maxLength={254} value={draft.personal.email ?? ''} onChange={(e) => setPersonal({ email: e.target.value })} /></label>
              <label className="field">Phone<input type="text" maxLength={40} value={draft.personal.phone ?? ''} onChange={(e) => setPersonal({ phone: e.target.value })} /></label>
              <label className="field">Location<input type="text" maxLength={120} value={draft.personal.location ?? ''} onChange={(e) => setPersonal({ location: e.target.value })} /></label>
              <label className="field alert-form-wide">Links (one per line)
                <textarea rows={2} value={(draft.personal.links ?? []).join('\n')} onChange={(e) => setPersonal({ links: lines(e.target.value) })} />
              </label>
            </div>
          )}
          {section === 'summary' && (
            <label className="field">Professional summary
              <textarea rows={5} maxLength={2000} value={draft.summary ?? ''} onChange={(e) => set({ summary: e.target.value })} />
            </label>
          )}
          {section === 'skills' && (
            <label className="field">Skills (comma-separated)
              <input type="text" value={(draft.skills ?? []).join(', ')}
                onChange={(e) => set({ skills: e.target.value.split(',').map((s) => s.trim()).filter(Boolean) })} />
            </label>
          )}
          {section === 'experience' && (
            <Entries label="experience" onAdd={() => experience.add({ title: '', company: '', current: false, bullets: [] })}>
              {experience.items.map((job, i) => (
                <Entry key={i} label={`Experience ${i + 1}`} onRemove={() => experience.remove(i)}>
                  <label className="field">Job title<input type="text" value={job.title} onChange={(e) => experience.update(i, { title: e.target.value })} /></label>
                  <label className="field">Company<input type="text" value={job.company} onChange={(e) => experience.update(i, { company: e.target.value })} /></label>
                  <label className="field">Location<input type="text" value={job.location ?? ''} onChange={(e) => experience.update(i, { location: e.target.value })} /></label>
                  <label className="field">Start<input type="text" maxLength={20} value={job.start ?? ''} onChange={(e) => experience.update(i, { start: e.target.value })} placeholder="Mar 2021" /></label>
                  <label className="field">End<input type="text" maxLength={20} value={job.end ?? ''} disabled={job.current} onChange={(e) => experience.update(i, { end: e.target.value })} /></label>
                  <label className="row small" style={{ gap: 6 }}><input type="checkbox" checked={job.current} onChange={(e) => experience.update(i, { current: e.target.checked })} />I work here now</label>
                  <label className="field alert-form-wide">Achievements and duties (one per line)
                    <textarea rows={4} value={(job.bullets ?? []).join('\n')} onChange={(e) => experience.update(i, { bullets: lines(e.target.value) })} />
                  </label>
                </Entry>
              ))}
            </Entries>
          )}
          {section === 'education' && (
            <Entries label="education" onAdd={() => education.add({ degree: '', institution: '' })}>
              {education.items.map((school, i) => (
                <Entry key={i} label={`Education ${i + 1}`} onRemove={() => education.remove(i)}>
                  <label className="field">Degree<input type="text" value={school.degree} onChange={(e) => education.update(i, { degree: e.target.value })} /></label>
                  <label className="field">Institution<input type="text" value={school.institution} onChange={(e) => education.update(i, { institution: e.target.value })} /></label>
                  <label className="field">Start<input type="text" maxLength={20} value={school.start ?? ''} onChange={(e) => education.update(i, { start: e.target.value })} /></label>
                  <label className="field">End<input type="text" maxLength={20} value={school.end ?? ''} onChange={(e) => education.update(i, { end: e.target.value })} /></label>
                  <label className="field alert-form-wide">Details<textarea rows={2} maxLength={600} value={school.details ?? ''} onChange={(e) => education.update(i, { details: e.target.value })} /></label>
                </Entry>
              ))}
            </Entries>
          )}
          {section === 'projects' && (
            <Entries label="project" onAdd={() => projects.add({ name: '', bullets: [] })}>
              {projects.items.map((project, i) => (
                <Entry key={i} label={`Project ${i + 1}`} onRemove={() => projects.remove(i)}>
                  <label className="field">Name<input type="text" value={project.name} onChange={(e) => projects.update(i, { name: e.target.value })} /></label>
                  <label className="field">Link<input type="text" value={project.url ?? ''} onChange={(e) => projects.update(i, { url: e.target.value })} /></label>
                  <label className="field alert-form-wide">Description<textarea rows={2} maxLength={800} value={project.description ?? ''} onChange={(e) => projects.update(i, { description: e.target.value })} /></label>
                  <label className="field alert-form-wide">Highlights (one per line)<textarea rows={3} value={(project.bullets ?? []).join('\n')} onChange={(e) => projects.update(i, { bullets: lines(e.target.value) })} /></label>
                </Entry>
              ))}
            </Entries>
          )}
          {section === 'certifications' && (
            <Entries label="certification" onAdd={() => certifications.add({ name: '' })}>
              {certifications.items.map((cert, i) => (
                <Entry key={i} label={`Certification ${i + 1}`} onRemove={() => certifications.remove(i)}>
                  <label className="field">Name<input type="text" value={cert.name} onChange={(e) => certifications.update(i, { name: e.target.value })} /></label>
                  <label className="field">Issuer<input type="text" value={cert.issuer ?? ''} onChange={(e) => certifications.update(i, { issuer: e.target.value })} /></label>
                  <label className="field">Date<input type="text" maxLength={20} value={cert.date ?? ''} onChange={(e) => certifications.update(i, { date: e.target.value })} /></label>
                </Entry>
              ))}
            </Entries>
          )}
          {section === 'achievements' && (
            <label className="field">Achievements (one per line)
              <textarea rows={5} value={(draft.achievements ?? []).join('\n')} onChange={(e) => set({ achievements: lines(e.target.value) })} />
            </label>
          )}
          {section === 'additional' && (
            <Entries label="section" onAdd={() => additional.add({ title: '', items: [] })}>
              {additional.items.map((extra, i) => (
                <Entry key={i} label={`Additional ${i + 1}`} onRemove={() => additional.remove(i)}>
                  <label className="field">Section title<input type="text" maxLength={60} value={extra.title} onChange={(e) => additional.update(i, { title: e.target.value })} placeholder="e.g. Languages" /></label>
                  <label className="field alert-form-wide">Items (one per line)<textarea rows={3} value={(extra.items ?? []).join('\n')} onChange={(e) => additional.update(i, { items: lines(e.target.value) })} /></label>
                </Entry>
              ))}
            </Entries>
          )}
        </Card>

        <Card title="Preview" description="As the PDF will look.">
          <ResumePreview content={draft} />
        </Card>
      </div>

      <Card title="Check against a job" description="Uses the saved version of this resume and one of your saved jobs. Suggestions only; nothing is added for you.">
        {dirty ? <p className="muted small">Save your changes to check them against a job.</p> : jobs.length === 0 ? (
          <p className="muted small">Save a job from the Job Explorer to compare this resume with it.</p>
        ) : (
          <>
            <label className="field">Job
              <select value={jobId} onChange={(e) => setJobId(e.target.value)}>
                <option value="">Choose a saved job</option>
                {jobs.map((item) => <option key={item.id} value={item.job.id}>{item.job.title} · {item.job.company.name}</option>)}
              </select>
            </label>
            {jobId && <ResumeOptimizer key={`${id}-${jobId}-${saved.length}`} resumeId={id} jobId={Number(jobId)} />}
          </>
        )}
      </Card>
    </>
  );
}

export function Entries({ label, onAdd, children }: { label: string; onAdd: () => void; children: React.ReactNode }) {
  return (
    <div className="stack" style={{ gap: 12 }}>
      {children}
      <button type="button" className="small" onClick={onAdd}>Add {label}</button>
    </div>
  );
}

export function Entry({ label, onRemove, children }: { label: string; onRemove: () => void; children: React.ReactNode }) {
  return (
    <fieldset className="builder-entry">
      <legend>{label}</legend>
      <div className="alert-form">{children}</div>
      <button type="button" className="small ghost" onClick={onRemove}>Remove {label.toLowerCase()}</button>
    </fieldset>
  );
}
