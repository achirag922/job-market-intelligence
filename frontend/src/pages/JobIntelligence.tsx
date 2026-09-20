import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { CategoryDemand, CompanyDemand, EntitySkill, LocationDemand } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { DemandBarChart } from '../components/DemandBarChart';
import { useApi } from '../hooks/useApi';

const BREAKDOWN_LIMIT = 8;

/**
 * Job categories and what characterises each one.
 *
 * <p>Picking a category loads its skills, locations and companies together, so the three
 * breakdowns always describe the same selection.
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

  return (
    <section>
      <h1>Job Intelligence</h1>
      <p className="subtitle">
        Postings grouped into role categories by a rule-based classifier reading the title,
        description and extracted skills. Percentages are of the postings that could be
        classified.
      </p>

      <div className="card">
        <h2>Category distribution</h2>
        <AsyncPanel
          state={categories}
          isEmpty={(data) => data.length === 0}
          empty="No postings have been classified yet. Run the ETL reprocessing job."
        >
          {(data) => (
            <>
              <DemandBarChart
                data={data.map((row) => ({ label: row.category, value: row.jobCount }))}
                height={Math.max(260, data.length * 30)}
              />
              <table>
                <thead>
                  <tr>
                    <th>Rank</th>
                    <th>Category</th>
                    <th>Job count</th>
                    <th>Percentage</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {data.map((row) => (
                    <tr key={row.category}>
                      <td>{row.rank}</td>
                      <td>{row.category}</td>
                      <td>{row.jobCount}</td>
                      <td>{row.percentageOfJobs.toFixed(1)}%</td>
                      <td>
                        <button
                          type="button"
                          onClick={() => setSelected(row.category)}
                        >
                          {selected === row.category ? 'Selected' : 'Explore'}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </AsyncPanel>
      </div>

      {selected && (
        <>
          <p className="subtitle">
            Showing <strong>{selected}</strong>. All percentages below are of that
            category&apos;s own postings.{' '}
            <Link to={`/jobs?category=${encodeURIComponent(selected)}`}>
              View these jobs
            </Link>
          </p>

          <div className="card">
            <h2>Top skills in {selected}</h2>
            <AsyncPanel
              state={skills}
              isEmpty={(data) => data.length === 0}
              empty="No skills were extracted from postings in this category."
            >
              {(data) => (
                <>
                  <DemandBarChart
                    data={data.map((row) => ({ label: row.skill, value: row.jobCount }))}
                    height={Math.max(240, data.length * 28)}
                  />
                  <table>
                    <thead>
                      <tr>
                        <th>Skill</th>
                        <th>Job count</th>
                        <th>Share of category</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.map((row) => (
                        <tr key={row.skillId}>
                          <td>{row.skill}</td>
                          <td>{row.jobCount}</td>
                          <td>{row.percentageOfJobs.toFixed(1)}%</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </>
              )}
            </AsyncPanel>
          </div>

          <div className="card">
            <h2>Where {selected} jobs are</h2>
            <AsyncPanel
              state={locations}
              isEmpty={(data) => data.length === 0}
              empty="Every posting in this category is remote, so none has a location."
            >
              {(data) => (
                <table>
                  <thead>
                    <tr>
                      <th>Location</th>
                      <th>Job count</th>
                      <th>Share of category</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.map((row) => (
                      <tr key={row.location.id}>
                        <td>{row.location.displayName}</td>
                        <td>{row.jobCount}</td>
                        <td>{row.percentageOfJobs.toFixed(1)}%</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </AsyncPanel>
          </div>

          <div className="card">
            <h2>Who is hiring for {selected}</h2>
            <AsyncPanel
              state={companies}
              isEmpty={(data) => data.length === 0}
              empty="No companies found for this category."
            >
              {(data) => (
                <table>
                  <thead>
                    <tr>
                      <th>Company</th>
                      <th>Industry</th>
                      <th>Job count</th>
                      <th>Share of category</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.map((row) => (
                      <tr key={row.company.id}>
                        <td>{row.company.name}</td>
                        <td>{row.company.industry ?? '—'}</td>
                        <td>{row.jobCount}</td>
                        <td>{row.percentageOfJobs.toFixed(1)}%</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </AsyncPanel>
          </div>
        </>
      )}
    </section>
  );
}
