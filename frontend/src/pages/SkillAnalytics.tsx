import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { PagedResponse, SkillDemand } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { DemandBarChart } from '../components/DemandBarChart';
import { Pagination } from '../components/Pagination';
import { useApi } from '../hooks/useApi';

const PAGE_SIZE = 15;

export function SkillAnalytics() {
  const [page, setPage] = useState(0);
  const skills = useApi<PagedResponse<SkillDemand>>(
    () => api.skillDemand(page, PAGE_SIZE),
    [page],
  );

  return (
    <section>
      <h1>Skill Analytics</h1>
      <p className="subtitle">
        Skills ranked by how many postings mention them. A posting usually needs several
        skills, so the percentages do not add up to 100.
      </p>

      <AsyncPanel
        state={skills}
        isEmpty={(data) => data.content.length === 0}
        empty="No skills have been extracted yet."
      >
        {(data) => (
          <>
            <div className="card">
              <DemandBarChart
                data={data.content.map((row) => ({ label: row.skill, value: row.jobCount }))}
                height={Math.max(260, data.content.length * 28)}
              />
            </div>

            <table>
              <thead>
                <tr>
                  <th>Skill</th>
                  <th>Category</th>
                  <th>Job count</th>
                  <th>Percentage of jobs</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((row) => (
                  <tr key={row.skillId}>
                    <td>{row.skill}</td>
                    <td>{row.category ?? '—'}</td>
                    <td>{row.jobCount}</td>
                    <td>{row.percentageOfJobs.toFixed(1)}%</td>
                    <td>
                      <Link to={`/jobs?skill=${encodeURIComponent(row.skill)}`}>View jobs</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <Pagination
              page={data.page}
              totalPages={data.totalPages}
              totalElements={data.totalElements}
              first={data.first}
              last={data.last}
              onChange={setPage}
            />
          </>
        )}
      </AsyncPanel>
    </section>
  );
}
