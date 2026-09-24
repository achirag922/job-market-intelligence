import { Link, useLocation, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { JobDetail } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { Badge, Card } from '../components/ui';
import { IconFile } from '../components/icons';
import {
  formatDate,
  formatEmploymentType,
  formatExperience,
  formatLocation,
  formatSalary,
} from '../components/format';
import { useApi } from '../hooks/useApi';
import { SaveJobButton } from '../saved/SavedJobs';

export function JobDetails() {
  const { id } = useParams<{ id: string }>();
  const jobId = Number(id);
  const job = useApi<JobDetail>(() => api.job(jobId), [jobId]);

  // Job Explorer hands over the search that led here, so "back" returns to those exact
  // results rather than an empty explorer. Opened directly — from a shared link, say —
  // there is no search to return to, and the plain explorer is the right destination.
  const location = useLocation();
  const from = (location.state as { from?: unknown } | null)?.from;
  const backTo = typeof from === 'string' && from.startsWith('?') ? `/jobs${from}` : '/jobs';

  return (
    <>
      <Link to={backTo} className="back-link">
        <span aria-hidden="true">←</span> {backTo === '/jobs' ? 'Back to Job Explorer' : 'Back to results'}
      </Link>

      <AsyncPanel state={job} skeleton="text">
        {(data) => (
          <>
            {/* The identity of the posting, and the one action worth offering from here. */}
            <div className="card job-hero">
              <div className="job-hero-main">
                <div className="row" style={{ marginBottom: 8 }}>
                  {data.classification ? (
                    <Badge tone="brand">{data.classification.category}</Badge>
                  ) : (
                    <Badge>Unclassified</Badge>
                  )}
                  <Badge>{formatEmploymentType(data.employmentType)}</Badge>
                </div>
                <h1>{data.title}</h1>
                <p className="subtitle" style={{ marginBottom: 0 }}>
                  {data.company.name}
                  {data.company.industry ? ` · ${data.company.industry}` : ''} ·{' '}
                  {formatLocation(data)}
                </p>
              </div>
              <div className="job-hero-actions">
                {/* Carries the posting through to the V3 comparison, which does the work. */}
                <Link className="button-link primary" to={`/resume?jobId=${data.id}`}>
                  <IconFile size={16} />
                  Compare with resume
                </Link>
                <SaveJobButton jobId={data.id} />
              </div>
            </div>

            <div className="card">
              <dl className="detail-grid">
                <div>
                  <dt>Location</dt>
                  <dd>{formatLocation(data)}</dd>
                </div>
                <div>
                  <dt>Experience</dt>
                  <dd>{formatExperience(data.experience)}</dd>
                </div>
                <div>
                  <dt>Salary</dt>
                  <dd>{formatSalary(data.salary)}</dd>
                </div>
                <div>
                  <dt>Employment type</dt>
                  <dd>{formatEmploymentType(data.employmentType)}</dd>
                </div>
                <div>
                  <dt>Posted</dt>
                  <dd>{formatDate(data.postedDate)}</dd>
                </div>
                <div>
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
                </div>
              </dl>
            </div>

            <Card title="Required skills" description="Extracted from the posting text.">
              {data.skills.length === 0 ? (
                <p className="status">No skills were extracted from this posting.</p>
              ) : (
                <ul className="skill-list" style={{ marginBottom: 0 }}>
                  {data.skills.map((skill) => (
                    <li key={skill.id} className="skill-tag">
                      {skill.name}
                    </li>
                  ))}
                </ul>
              )}
            </Card>

            {data.classification && (
              <Card
                title="Why this category?"
                description="Assigned by matching the title, description and extracted skills against a fixed set of rules. The confidence reflects how much evidence matched and how clearly it beat the other categories — it is not a probability that the category is right."
                actions={
                  data.classification.confidence !== undefined ? (
                    <Badge tone="brand">
                      {data.classification.confidence.toFixed(0)}% confidence
                    </Badge>
                  ) : undefined
                }
              >
                {data.classification.signals.length === 0 ? (
                  <p className="status">No signals were recorded for this posting.</p>
                ) : (
                  <div className="table-wrap" style={{ marginBottom: 0 }}>
                    <table>
                      <thead>
                        <tr>
                          <th scope="col">Matched</th>
                          <th scope="col">Found in</th>
                          <th scope="col" className="numeric">
                            Weight
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.classification.signals.map((signal) => (
                          <tr key={`${signal.type}-${signal.value}`}>
                            <td>{signal.value}</td>
                            <td>{formatSignalType(signal.type)}</td>
                            <td className="numeric">{signal.weight}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </Card>
            )}

            <Card title="Job description">
              {/* Rendered as plain text, never as HTML: the description comes from an
                  external source and must not be able to inject markup. */}
              <p className="description">{data.description ?? 'No description provided.'}</p>
            </Card>
          </>
        )}
      </AsyncPanel>
    </>
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
