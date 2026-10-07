import type { BuilderContent } from '../api/types';

function dates(start?: string, end?: string, current?: boolean): string | null {
  const until = current ? 'Present' : end;
  if (!start && !until) {
    return null;
  }
  return !start ? until! : !until ? start : `${start} – ${until}`;
}

function join(separator: string, ...parts: (string | undefined | null)[]): string {
  return parts.filter((part) => part && part.trim()).join(separator);
}

/**
 * V9.4: the resume as the PDF will print it, in the chosen template. Plain, single-column HTML;
 * only what the user wrote, in the order the export uses.
 */
/** One titled block of the resume; module level, so the preview is not rebuilt from scratch on every keystroke. */
function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="resume-section">
      <h3>{title}</h3>
      {children}
    </section>
  );
}

export function ResumePreview({ content }: { content: BuilderContent }) {
  const { personal } = content;
  return (
    <article className={`resume-sheet ${content.template === 'MODERN' ? 'resume-modern' : 'resume-classic'}`} aria-label="Resume preview">
      <header className="resume-head">
        <h2>{personal.fullName || 'Your Name'}</h2>
        {personal.headline && <p className="resume-headline">{personal.headline}</p>}
        {(personal.email || personal.phone || personal.location) && (
          <p className="resume-contact">{join('  |  ', personal.email, personal.phone, personal.location)}</p>
        )}
        {(personal.links ?? []).map((link) => <p key={link} className="resume-contact">{link}</p>)}
      </header>
      {content.summary && <Section title="Summary"><p>{content.summary}</p></Section>}
      {(content.skills ?? []).length > 0 && <Section title="Skills"><p>{content.skills!.join(' · ')}</p></Section>}
      {(content.experience ?? []).length > 0 && (
        <Section title="Experience">
          {content.experience!.map((job, index) => (
            <div key={index} className="resume-entry">
              <div className="resume-entry-head"><strong>{job.title}</strong><span>{dates(job.start, job.end, job.current)}</span></div>
              {(job.company || job.location) && <p className="resume-sub">{join(' · ', job.company, job.location)}</p>}
              {(job.bullets ?? []).length > 0 && <ul>{job.bullets!.map((bullet, i) => <li key={i}>{bullet}</li>)}</ul>}
            </div>
          ))}
        </Section>
      )}
      {(content.education ?? []).length > 0 && (
        <Section title="Education">
          {content.education!.map((school, index) => (
            <div key={index} className="resume-entry">
              <div className="resume-entry-head"><strong>{school.degree}</strong><span>{dates(school.start, school.end)}</span></div>
              <p className="resume-sub">{join(' · ', school.institution, school.location)}</p>
              {school.details && <p>{school.details}</p>}
            </div>
          ))}
        </Section>
      )}
      {(content.projects ?? []).length > 0 && (
        <Section title="Projects">
          {content.projects!.map((project, index) => (
            <div key={index} className="resume-entry">
              <div className="resume-entry-head"><strong>{project.name}</strong></div>
              {project.url && <p className="resume-sub">{project.url}</p>}
              {project.description && <p>{project.description}</p>}
              {(project.bullets ?? []).length > 0 && <ul>{project.bullets!.map((bullet, i) => <li key={i}>{bullet}</li>)}</ul>}
            </div>
          ))}
        </Section>
      )}
      {(content.certifications ?? []).length > 0 && (
        <Section title="Certifications">
          {content.certifications!.map((cert, index) => (
            <div key={index} className="resume-entry">
              <div className="resume-entry-head"><strong>{cert.name}</strong><span>{cert.date}</span></div>
              {cert.issuer && <p className="resume-sub">{cert.issuer}</p>}
            </div>
          ))}
        </Section>
      )}
      {(content.achievements ?? []).length > 0 && (
        <Section title="Achievements"><ul>{content.achievements!.map((item, i) => <li key={i}>{item}</li>)}</ul></Section>
      )}
      {(content.additional ?? []).map((extra, index) => (
        <Section key={index} title={extra.title || 'Additional'}>
          <ul>{(extra.items ?? []).map((item, i) => <li key={i}>{item}</li>)}</ul>
        </Section>
      ))}
    </article>
  );
}
