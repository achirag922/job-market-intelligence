import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { CategoryDemand, CompanyDemand, EntitySkill, LocationDemand } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { BarChartPanel } from '../components/charts';
import { BarCell, Card, EmptyState, PageHeader, StatCard } from '../components/ui';
import { useApi } from '../hooks/useApi';

const BREAKDOWN_LIMIT = 8;

/**
 * Job categories and what characterises each one.
 *
 * <p>Picking a category loads its skills, locations and companies together, so the three
 * breakdowns always describe the same selection — a stale panel beside a fresh one would
 * be a quietly wrong answer.
 */
export function JobIntelligence() {
  const [selected, setSelected] = useState<string | null>(null);

  const categories = useApi<CategoryDemand[]>(() => api.jobCategories(), []);

  // Every breakdown is keyed on the selection, so switching category refreshes all three.
  const skills = useApi<EntitySkill[]>(
    () => (selected ? api.categorySkills(selected, BREAKDOWN_LIMIT) : Promise.resolve([])),
    [selected],
  );
  const locations = useApi<LocationDemand[]>(
    () => (selected ? api.categoryLocations(selected, BREAKDOWN_LIMIT) : Promise.resolve([])),
    [selected],
  );
  const companies = useApi<CompanyDemand[]>(
    () => (selected ? api.categoryCompanies(selected, BREAKDOWN_LIMIT) : Promise.resolve([])),
    [selected],
  );

  const selectedRow = categories.data?.find((row) => row.category === selected);

  return (
    <>
      <PageHeader
        title="Job Categories"
        description="Postings grouped into role categories by a rule-based classifier reading the title, description and extracted skills. Percentages are of the postings that could be classified."
      />

      <AsyncPanel
        state={categories}
        skeleton="cards"
        skeletonCount={3}
        isEmpty={(data) => data.length === 0}
        emptyTitle="Nothing classified yet"
        empty="No postings carry a category. Run the ETL reprocessing job to classify them."
      >
        {(data) => {
          const topCount = data[0]?.jobCount ?? 0;
          const classified = data.reduce((sum, row) => sum + row.jobCount, 0);
          return (
            <>
              <div className="stat-grid">
                <StatCard label="Categories in use" value={data.length} hint="With at least one posting" />
                <StatCard label="Classified postings" value={classified} hint="The denominator below" />
                <StatCard
                  label="Largest category"
                  value={data[0]?.category ?? null}
                  hint={data[0] ? `${data[0].percentageOfJobs.toFixed(1)}% of classified postings` : undefined}
                />
              </div>

              <Card
                title="Category distribution"
                description="Each posting belongs to exactly one category, so these shares make up a whole."
                actions={
                  <label className="field" style={{ minWidth: 220 }}>
                    <span className="visually-hidden">Choose a category to explore</span>
                    <select
                      value={selected ?? ''}
                      aria-label="Choose a category to explore"
                      onChange={(event) => setSelected(event.target.value || null)}
                    >
                      <option value="">Explore a category…</option>
                      {data.map((row) => (
                        <option key={row.category} value={row.category}>
                          {row.category} ({row.jobCount})
                        </option>
                      ))}
                    </select>
                  </label>
                }
              >
                <BarChartPanel
                  data={data.map((row) => ({ label: row.category, value: row.jobCount }))}
                  valueLabel="Postings"
                />

                <div className="table-wrap" style={{ marginTop: 20, marginBottom: 0 }}>
                  <table>
                    <caption className="visually-hidden">Categories ranked by posting count</caption>
                    <thead>
                      <tr>
                        <th scope="col" className="rank-cell">
                          #
                        </th>
                        <th scope="col">Category</th>
                        <th scope="col">Postings</th>
                        <th scope="col" className="numeric">
                          Share
                        </th>
                        <th scope="col">
                          <span className="visually-hidden">Actions</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.map((row) => (
                        <tr key={row.category}>
                          <td className="rank-cell">{row.rank}</td>
                          <td className="cell-strong">{row.category}</td>
                          <td className="bar-cell">
                            <BarCell value={row.jobCount} max={topCount} />
                          </td>
                          <td className="numeric">{row.percentageOfJobs.toFixed(1)}%</td>
                          <td>
                            <button
                              type="button"
                              className="small"
                              aria-pressed={selected === row.category}
                              onClick={() => setSelected(row.category)}
                            >
                              {selected === row.category ? 'Selected' : 'Explore'}
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Card>

              {!selected ? (
                <Card>
                  <EmptyState
                    title="Pick a category"
                    message="Choose a category above to see the skills it asks for, where its postings are, and who is hiring."
                  />
                </Card>
              ) : (
                <>
                  <div className="selection-banner">
                    <div>
                      <strong>{selected}</strong>
                      {selectedRow && (
                        <span className="muted">
                          {' '}
                          · {selectedRow.jobCount} postings ·{' '}
                          {selectedRow.percentageOfJobs.toFixed(1)}% of classified
                        </span>
                      )}
                      <p className="card-description" style={{ margin: '2px 0 0' }}>
                        Every percentage below is a share of this category's own postings.
                      </p>
                    </div>
                    <Link
                      className="button-link"
                      to={`/jobs?category=${encodeURIComponent(selected)}`}
                    >
                      View these jobs
                    </Link>
                  </div>

                  <div className="chart-grid">
                    <Card title="Top skills" description={`Most asked for in ${selected}.`}>
                      <AsyncPanel
                        state={skills}
                        skeleton="chart"
                        isEmpty={(rows) => rows.length === 0}
                        empty="No skills were extracted from this category's postings."
                      >
                        {(rows) => (
                          <BreakdownTable
                            caption="Top skills"
                            head="Skill"
                            rows={rows.map((row) => ({
                              key: String(row.skillId),
                              label: row.skill,
                              count: row.jobCount,
                              share: row.percentageOfJobs,
                            }))}
                          />
                        )}
                      </AsyncPanel>
                    </Card>

                    <Card title="Top locations" description="Remote postings have no location.">
                      <AsyncPanel
                        state={locations}
                        skeleton="chart"
                        isEmpty={(rows) => rows.length === 0}
                        empty="No posting in this category carries a location."
                      >
                        {(rows) => (
                          <BreakdownTable
                            caption="Top locations"
                            head="Location"
                            rows={rows.map((row) => ({
                              key: String(row.location.id),
                              label: row.location.displayName,
                              count: row.jobCount,
                              share: row.percentageOfJobs,
                            }))}
                          />
                        )}
                      </AsyncPanel>
                    </Card>

                    <Card title="Who is hiring" description={`Companies posting ${selected} roles.`}>
                      <AsyncPanel
                        state={companies}
                        skeleton="chart"
                        isEmpty={(rows) => rows.length === 0}
                        empty="No companies are hiring for this category."
                      >
                        {(rows) => (
                          <BreakdownTable
                            caption="Top companies"
                            head="Company"
                            rows={rows.map((row) => ({
                              key: String(row.company.id),
                              label: row.company.name,
                              count: row.jobCount,
                              share: row.percentageOfJobs,
                            }))}
                          />
                        )}
                      </AsyncPanel>
                    </Card>
                  </div>
                </>
              )}
            </>
          );
        }}
      </AsyncPanel>
    </>
  );
}

interface BreakdownRow {
  key: string;
  label: string;
  count: number;
  share: number;
}

/** The three category panels are the same shape, so they share one table. */
function BreakdownTable({
  caption,
  head,
  rows,
}: {
  caption: string;
  head: string;
  rows: BreakdownRow[];
}) {
  const max = rows[0]?.count ?? 0;
  return (
    <div className="table-wrap" style={{ marginBottom: 0 }}>
      <table>
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">{head}</th>
            <th scope="col">Postings</th>
            <th scope="col" className="numeric">
              Share
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.key}>
              <td className="wrap">{row.label}</td>
              <td className="bar-cell">
                <BarCell value={row.count} max={max} />
              </td>
              <td className="numeric">{row.share.toFixed(1)}%</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
