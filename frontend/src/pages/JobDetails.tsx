import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { JobDetail } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import {
  formatDate,
  formatEmploymentType,
  formatExperience,
  formatLocation,
  formatSalary,
} from '../components/format';
import { useApi } from '../hooks/useApi';

export function JobDetails() {
  const { id } = useParams<{ id: string }>();
  const jobId = Number(id);
  const job = useApi<JobDetail>(() => api.job(jobId), [jobId]);

  return (
    <section>
      <Link to="/jobs" className="back-link">
        ← Back to Job Explorer
      </Link>

      <AsyncPanel state={job}>
        {(data) => (
          <>
            <h1>{data.title}</h1>
            <p className="subtitle">
              {data.company.name}
              {data.company.industry ? ` · ${data.company.industry}` : ''}
            </p>

            <dl className="detail-grid">
              <dt>Location</dt>
              <dd>{formatLocation(data)}</dd>

              <dt>Experience</dt>
              <dd>{formatExperience(data.experience)}</dd>

              <dt>Salary</dt>
              <dd>{formatSalary(data.salary)}</dd>

              <dt>Employment type</dt>
              <dd>{formatEmploymentType(data.employmentType)}</dd>

              <dt>Posted</dt>
              <dd>{formatDate(data.postedDate)}</dd>

              <dt>Source</dt>
              <dd>
                {data.sourceUrl ? (
                  <a href={data.sourceUrl} target="_blank" rel="noreferrer">
                    {data.source}
                  </a>
                ) : (
                  data.source
                )}
              </dd>
            </dl>

            {data.classification && (
              <div className="card">
                <h2>
                  Category: {data.classification.category}
                  {data.classification.confidence !== undefined && (
                    <span className="trend trend-stable">
                      {' '}
                      · {data.classification.confidence.toFixed(0)}% confidence
                    </span>
                  )}
                </h2>
                <p className="subtitle">
                  Assigned by matching the title, description and extracted skills against
                  a fixed set of rules. The confidence reflects how much evidence matched
                  and how clearly it beat the other categories — it is not a probability
                  that the category is right.
                </p>

                <h2>Why this category?</h2>
                {data.classification.signals.length === 0 ? (
                  <p className="status">No signals were recorded for this posting.</p>
                ) : (
                  <table>
                    <thead>
                      <tr>
                        <th>Matched</th>
                        <th>Found in</th>
                        <th>Weight</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.classification.signals.map((signal) => (
                        <tr key={`${signal.type}-${signal.value}`}>
                          <td>{signal.value}</td>
                          <td>{formatSignalType(signal.type)}</td>
                          <td>{signal.weight}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            )}

            <h2>Skills</h2>
            {data.skills.length === 0 ? (
              <p className="status">No skills were extracted from this posting.</p>
            ) : (
              <ul className="skill-list">
                {data.skills.map((skill) => (
                  <li key={skill.id} className="skill-tag">
                    {skill.name}
                  </li>
                ))}
              </ul>
            )}

            <h2>Description</h2>
            {/* Rendered as plain text, never as HTML: the description comes from an
                external source and must not be able to inject markup. */}
            <p className="description">{data.description ?? 'No description provided.'}</p>
          </>
        )}
      </AsyncPanel>
    </section>
  );
}

/** TITLE / DESCRIPTION / SKILL read better as plain words next to the matched value. */
function formatSignalType(type: string): string {
  switch (type) {
    case 'TITLE':
      return 'Job title';
    case 'DESCRIPTION':
      return 'Description';
    case 'SKILL':
      return 'Required skill';
    default:
      return type;
  }
}
