import { useState } from 'react';
import { ApiError, api } from '../api/client';
import type { AdminDataQuality, AdminOverview, AdminUserDetail, AdminUserSummary, EtlRunOutcome, PagedResponse } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { DebouncedInput } from '../components/DebouncedInput';
import { Pagination } from '../components/Pagination';
import { Badge, Card, PageHeader, StatCard } from '../components/ui';
import { useApi } from '../hooks/useApi';

const PAGE_SIZE = 20;

const OUTCOME_TONE: Record<EtlRunOutcome, 'success' | 'danger' | 'brand' | 'warning'> = {
  SUCCEEDED: 'success',
  FAILED: 'danger',
  RUNNING: 'brand',
  STOPPED: 'warning',
};

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

function when(iso?: string | null): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

/**
 * V8.9: platform administration, for ADMIN accounts only. The server decides access; this page
 * only shows what the admin APIs return, which never includes passwords, codes or resume contents.
 */
export function AdminDashboard() {
  const [refresh, setRefresh] = useState(0);
  const overview = useApi<AdminOverview>(() => api.adminOverview(), [refresh]);
  const quality = useApi<AdminDataQuality>(() => api.adminDataQuality(), [refresh]);
  const reload = () => setRefresh((count) => count + 1);

  return (
    <>
      <PageHeader
        title="Admin Dashboard"
        description="Accounts, ingestion, job sources, data quality and system health. Visible to admins only."
        actions={<button type="button" onClick={reload}>Refresh</button>}
      />

      <AsyncPanel state={overview} onRetry={reload} skeleton="cards" skeletonCount={4}>
        {(data) => <OverviewPanel data={data} onChanged={reload} />}
      </AsyncPanel>

      <Card title="Data quality" description="From the V8.2 ingestion metrics, over every recorded ingestion run.">
        <AsyncPanel state={quality} onRetry={reload} skeleton="table" skeletonCount={4}>
          {(data) => <QualityPanel data={data} />}
        </AsyncPanel>
      </Card>

      <UsersPanel />
    </>
  );
}

function OverviewPanel({ data, onChanged }: { data: AdminOverview; onChanged: () => void }) {
  const latest = data.etl.latest;
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);

  const toggle = async (id: number, active: boolean) => {
    setBusy(id);
    setError(null);
    try {
      await api.setJobSourceActive(id, active);
      onChanged();
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <Card title="Platform">
        <div className="stat-grid">
          <StatCard label="Users" value={data.users.total} hint={`${data.users.verified} verified · ${data.users.admins} admin(s)`} />
          <StatCard label="New users (30 days)" value={data.users.newLast30Days} />
          <StatCard label="Active users (30 days)" value={data.users.activeLast30Days} hint={data.users.activeDefinition} />
          <StatCard label="Jobs" value={data.jobs.total} hint={`${data.jobs.active} active · ${data.jobs.inactive} inactive`} />
          <StatCard label="New jobs (7 days)" value={data.jobs.firstSeenLast7Days} hint={`${data.jobs.firstSeenLast30Days} in 30 days`} />
          <StatCard label="Failed ETL runs (30 days)" value={data.etl.failedLast30Days} />
        </div>
      </Card>

      <Card title="Latest ETL run" description="Spring Batch's own record of the most recent run.">
        {latest ? (
          <div className="stack" style={{ gap: 8 }}>
            <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
              <Badge tone={OUTCOME_TONE[latest.outcome] ?? 'warning'}>{latest.outcome}</Badge>
              <span className="muted small">Execution #{latest.executionId} · started {when(latest.startTime)}</span>
              {latest.feedName && <span className="muted small">Feed {latest.feedName}</span>}
            </div>
            {latest.outcome === 'FAILED' && latest.exitMessage && (
              <p className="status status-error" role="alert" style={{ margin: 0 }}>{latest.exitMessage}</p>
            )}
            <p className="small" style={{ margin: 0 }}>
              Read {latest.recordsRead} · processed {latest.recordsProcessed} · loaded {latest.recordsLoaded ?? '—'} ·
              duplicates {latest.duplicates ?? '—'} · rejected {latest.rejected} · expired {latest.expired ?? '—'}
            </p>
          </div>
        ) : (
          <p className="muted small">No ETL run has been recorded yet.</p>
        )}
      </Card>

      <Card title="Job sources" description="Turning a source off makes the ETL reject its records from the next run; stored jobs stay.">
        {error && <p className="status status-error" role="alert">{error}</p>}
        {data.sources.length === 0 ? (
          <p className="muted small">No job source has been registered yet.</p>
        ) : (
          <div className="table-wrap">
            <table>
              <caption className="visually-hidden">Job sources</caption>
              <thead>
                <tr>
                  <th scope="col">Source</th>
                  <th scope="col">Type</th>
                  <th scope="col">Status</th>
                  <th scope="col" className="tabular">Postings</th>
                  <th scope="col">Last ingested</th>
                  <th scope="col"><span className="visually-hidden">Action</span></th>
                </tr>
              </thead>
              <tbody>
                {data.sources.map((source) => (
                  <tr key={source.id}>
                    <td>{source.name}</td>
                    <td>{source.sourceType}</td>
                    <td><Badge tone={source.active ? 'success' : 'warning'}>{source.active ? 'Active' : 'Inactive'}</Badge></td>
                    <td className="tabular">{source.jobCount}</td>
                    <td>{when(source.lastIngestedAt)}</td>
                    <td>
                      <button type="button" className="small ghost" disabled={busy === source.id}
                        onClick={() => toggle(source.id, !source.active)}>
                        {source.active ? `Disable ${source.name}` : `Enable ${source.name}`}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card title="System health and activity" description={`Actuator health, and what happened in the last ${data.activity.days} days.`}>
        <div className="row" style={{ gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
          <Badge tone={data.health.status === 'UP' ? 'success' : 'danger'}>Overall {data.health.status}</Badge>
          {Object.entries(data.health.components).map(([name, status]) => (
            <Badge key={name} tone={status === 'UP' ? 'neutral' : 'danger'}>{name}: {status}</Badge>
          ))}
        </div>
        <div className="stat-grid">
          <StatCard label="Sign-ups" value={data.activity.signups} />
          <StatCard label="Resumes uploaded" value={data.activity.resumesUploaded} />
          <StatCard label="Jobs saved" value={data.activity.jobsSaved} />
          <StatCard label="Applications" value={data.activity.applications} />
          <StatCard label="Interview sessions" value={data.activity.interviewSessions} />
          <StatCard label="Jobs emailed in alerts" value={data.activity.jobsEmailedInAlerts} />
        </div>
      </Card>
    </>
  );
}

function QualityPanel({ data }: { data: AdminDataQuality }) {
  return (
    <div className="stack" style={{ gap: 12 }}>
      <div className="stat-grid">
        <StatCard label="Ingestion runs" value={data.totals.ingestionRuns} />
        <StatCard label="Valid records" value={data.totals.validRecords} hint={`of ${data.totals.recordsRead} read`} />
        <StatCard label="Loaded" value={data.totals.loaded} />
        <StatCard label="Duplicates" value={data.totals.duplicates} />
        <StatCard label="Rejected" value={data.totals.rejected} />
        <StatCard label="Expired" value={data.totals.expired} hint={`${data.current.inactive} jobs inactive now`} />
      </div>
      <div className="gap-columns">
        <div className="gap-column">
          <h3 className="small">Top rejection reasons</h3>
          {data.topRejectionReasons.length === 0 ? (
            <p className="muted small">No record has been rejected.</p>
          ) : (
            <ol className="small" style={{ margin: 0, paddingLeft: 18 }}>
              {data.topRejectionReasons.map((reason) => (
                <li key={reason.reason}>{reason.reason} <span className="muted">({reason.count})</span></li>
              ))}
            </ol>
          )}
        </div>
        <div className="gap-column">
          <h3 className="small">Jobs now</h3>
          <p className="small" style={{ margin: 0 }}>
            {data.current.active} active · {data.current.expiredByDate} expired by date · {data.current.closedBySource} closed by source
          </p>
        </div>
      </div>
      <div className="table-wrap">
        <table>
          <caption className="visually-hidden">Quality by source</caption>
          <thead>
            <tr>
              <th scope="col">Source</th>
              <th scope="col" className="tabular">Jobs</th>
              <th scope="col" className="tabular">Active</th>
              <th scope="col" className="tabular">Inactive</th>
              <th scope="col" className="tabular">Loaded</th>
              <th scope="col" className="tabular">Seen again</th>
            </tr>
          </thead>
          <tbody>
            {data.sources.map((source) => (
              <tr key={source.sourceId}>
                <td>{source.name}</td>
                <td className="tabular">{source.jobs}</td>
                <td className="tabular">{source.activeJobs}</td>
                <td className="tabular">{source.inactiveJobs}</td>
                <td className="tabular">{source.loaded}</td>
                <td className="tabular">{source.seenAgain}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {data.notes.map((note) => <p key={note} className="muted small" style={{ margin: 0 }}>{note}</p>)}
    </div>
  );
}

function UsersPanel() {
  const [query, setQuery] = useState('');
  const [role, setRole] = useState('');
  const [verified, setVerified] = useState('');
  const [page, setPage] = useState(0);
  const [detail, setDetail] = useState<AdminUserDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const users = useApi<PagedResponse<AdminUserSummary>>(
    () => api.adminUsers({ q: query || undefined, role: role || undefined, verified: verified || undefined, page, size: PAGE_SIZE }),
    [query, role, verified, page],
  );

  const open = async (id: string) => {
    setError(null);
    try {
      setDetail(await api.adminUser(id));
    } catch (cause) {
      setError(messageOf(cause));
    }
  };

  return (
    <Card title="Users" description="Account metadata only. Passwords, sign-in sessions, codes and resume contents are never shown.">
      <div className="row" style={{ gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
        <label className="field" style={{ flex: '1 1 220px' }}>
          Search
          <DebouncedInput value={query} onCommit={(value: string) => { setQuery(value); setPage(0); }} placeholder="Name or email" />
        </label>
        <label className="field">
          Role
          <select value={role} onChange={(event) => { setRole(event.target.value); setPage(0); }}>
            <option value="">Any role</option>
            <option value="USER">User</option>
            <option value="ADMIN">Admin</option>
          </select>
        </label>
        <label className="field">
          Email
          <select value={verified} onChange={(event) => { setVerified(event.target.value); setPage(0); }}>
            <option value="">Verified or not</option>
            <option value="true">Verified</option>
            <option value="false">Not verified</option>
          </select>
        </label>
      </div>
      {error && <p className="status status-error" role="alert">{error}</p>}
      <AsyncPanel state={users} skeleton="table" skeletonCount={5} isEmpty={(data) => data.content.length === 0}
        emptyTitle="No users" empty="No account matches these filters.">
        {(data) => (
          <>
            <div className="table-wrap">
              <table>
                <caption className="visually-hidden">Users</caption>
                <thead>
                  <tr>
                    <th scope="col">Email</th>
                    <th scope="col">Name</th>
                    <th scope="col">Role</th>
                    <th scope="col">Email verified</th>
                    <th scope="col">Created</th>
                    <th scope="col"><span className="visually-hidden">Action</span></th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((account) => (
                    <tr key={account.id}>
                      <td>{account.email}</td>
                      <td>{account.fullName ?? '—'}</td>
                      <td><Badge tone={account.role === 'ADMIN' ? 'brand' : 'neutral'}>{account.role}</Badge></td>
                      <td>{account.emailVerified ? 'Yes' : 'No'}</td>
                      <td>{when(account.createdAt)}</td>
                      <td>
                        <button type="button" className="small ghost" onClick={() => open(account.id)}>
                          Details for {account.email}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements}
              first={data.first} last={data.last} onChange={setPage} />
          </>
        )}
      </AsyncPanel>
      {detail && (
        <section aria-label="Account details" className="stack" style={{ gap: 6, marginTop: 12 }}>
          <h3 className="small">{detail.account.email}</h3>
          <p className="small" style={{ margin: 0 }}>
            {detail.account.role} · email {detail.account.emailVerified ? 'verified' : 'not verified'} · created {when(detail.account.createdAt)}
            {' '}· last activity {when(detail.lastActivityAt)}
          </p>
          <p className="small" style={{ margin: 0 }}>
            {detail.resumes} resume(s) · {detail.savedJobs} saved job(s) · {detail.applications} application(s) ·{' '}
            {detail.jobAlerts} alert(s) · {detail.interviewSessions} interview session(s) · {detail.careerGoals} career goal(s)
          </p>
          {detail.note && <p className="muted small" style={{ margin: 0 }}>{detail.note}</p>}
        </section>
      )}
    </Card>
  );
}
