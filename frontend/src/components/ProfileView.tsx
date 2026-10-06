import type { PublicProfile } from '../api/types';

function dates(start?: string, end?: string, current?: boolean): string | null {
  const to = current ? 'Present' : end;
  return start || to ? [start, to].filter(Boolean).join(' – ') : null;
}

/**
 * V9.7: a professional profile as the public sees it. Used for the owner's live preview and the
 * public page, so both always look the same. Everything renders as text, never as HTML.
 */
export function ProfileView({ profile }: { profile: PublicProfile }) {
  return (
    <article className="profile-view" aria-label={`Profile of ${profile.displayName}`}>
      <header className="profile-view-header">
        <h1>{profile.displayName}</h1>
        {profile.headline && <p className="subtitle">{profile.headline}</p>}
        {profile.links && (
          <ul className="profile-links" aria-label="Links">
            {profile.links.map((link) => (
              <li key={link.url}>
                <a href={link.url} target="_blank" rel="noopener noreferrer nofollow">{link.label}</a>
              </li>
            ))}
          </ul>
        )}
      </header>

      {profile.about && (
        <section>
          <h2>About</h2>
          <p className="profile-about">{profile.about}</p>
        </section>
      )}
      {profile.careerGoals && (
        <section>
          <h2>Career goals</h2>
          <p>Working towards: {profile.careerGoals.join(', ')}</p>
        </section>
      )}
      {profile.skills && (
        <section>
          <h2>Skills</h2>
          <ul className="skill-list" aria-label="Skills">
            {profile.skills.map((skill) => <li key={skill} className="skill-tag">{skill}</li>)}
          </ul>
        </section>
      )}
      {profile.experience && (
        <section>
          <h2>Experience</h2>
          {profile.experience.map((job, index) => (
            <div key={index} className="profile-entry">
              <strong>{job.title}</strong> · {job.company}
              {(dates(job.start, job.end, job.current) || job.location) && (
                <p className="muted small">{[dates(job.start, job.end, job.current), job.location].filter(Boolean).join(' · ')}</p>
              )}
              {job.bullets && job.bullets.length > 0 && <ul>{job.bullets.map((bullet) => <li key={bullet}>{bullet}</li>)}</ul>}
            </div>
          ))}
        </section>
      )}
      {profile.projects && (
        <section>
          <h2>Projects</h2>
          {profile.projects.map((project, index) => (
            <div key={index} className="profile-entry">
              <strong>{project.url ? <a href={project.url} target="_blank" rel="noopener noreferrer nofollow">{project.name}</a> : project.name}</strong>
              {project.description && <p className="small">{project.description}</p>}
              {project.bullets && project.bullets.length > 0 && <ul>{project.bullets.map((bullet) => <li key={bullet}>{bullet}</li>)}</ul>}
            </div>
          ))}
        </section>
      )}
      {profile.education && (
        <section>
          <h2>Education</h2>
          {profile.education.map((school, index) => (
            <div key={index} className="profile-entry">
              <strong>{school.degree}</strong> · {school.institution}
              {dates(school.start, school.end) && <p className="muted small">{dates(school.start, school.end)}</p>}
              {school.details && <p className="small">{school.details}</p>}
            </div>
          ))}
        </section>
      )}
      {profile.certifications && (
        <section>
          <h2>Certifications</h2>
          <ul>
            {profile.certifications.map((cert, index) => (
              <li key={index}>{[cert.name, cert.issuer, cert.date].filter(Boolean).join(' · ')}</li>
            ))}
          </ul>
        </section>
      )}
      {profile.achievements && (
        <section>
          <h2>Achievements</h2>
          <ul>{profile.achievements.map((item) => <li key={item}>{item}</li>)}</ul>
        </section>
      )}
    </article>
  );
}
