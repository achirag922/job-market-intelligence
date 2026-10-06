import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { ApiError } from '../api/client';
import type { CareerInsights as Insights } from '../api/types';
import { CareerInsights } from './CareerInsights';

const careerInsights = vi.fn();
const jobCategories = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      careerInsights: (...args: unknown[]) => careerInsights(...args),
      jobCategories: (...args: unknown[]) => jobCategories(...args),
    },
  };
});

const RESUME_ID = '90776dbd-97b0-48c9-92f7-51391abf9ea1';

function insights(overrides: Partial<Insights> = {}): Insights {
  return {
    resumeId: RESUME_ID,
    targetCategory: 'Data Engineer',
    categorySource: 'TOP_RECOMMENDATION',
    resumeSkills: [
      { id: 1, name: 'Python', category: 'LANGUAGE' },
      { id: 4, name: 'Java', category: 'LANGUAGE' },
    ],
    highDemandSkills: [
      { skillId: 1, skill: 'Python', jobCount: 3, percentageOfJobs: 75, rank: 1, onResume: true },
      { skillId: 2, skill: 'SQL', jobCount: 2, percentageOfJobs: 50, rank: 2, onResume: false },
    ],
    strongSkills: [{ skillId: 1, skill: 'Python', jobCount: 3, percentageOfJobs: 75, rank: 1, onResume: true }],
    skillGaps: [{ skillId: 2, skill: 'SQL', jobCount: 2, percentageOfJobs: 50, rank: 2, onResume: false }],
    trendingSkills: [
      {
        skillId: 2,
        skill: 'SQL',
        earlierSharePercentage: 10,
        recentSharePercentage: 14.5,
        changeInPercentagePoints: 4.5,
        onResume: false,
      },
    ],
    focusAreas: [{ skillId: 2, skill: 'SQL', percentageOfJobs: 50, demandRank: 2, changeInPercentagePoints: 4.5 }],
    recommendedJobs: [
      {
        jobId: 5,
        jobTitle: 'Data Engineer II',
        companyName: 'Acme Systems',
        jobCategory: 'Data Engineer',
        matchPercentage: 100,
        matchedSkills: [],
        missingSkills: [],
      },
    ],
    ...overrides,
  };
}

function renderInsights(onShowRecommendations = vi.fn()) {
  render(
    <MemoryRouter>
      <CareerInsights resumeId={RESUME_ID} onShowRecommendations={onShowRecommendations} />
    </MemoryRouter>,
  );
  return onShowRecommendations;
}

describe('CareerInsights', () => {
  beforeEach(() => {
    careerInsights.mockReset();
    jobCategories.mockReset();
    careerInsights.mockResolvedValue(insights());
    jobCategories.mockResolvedValue([
      { category: 'Data Engineer', jobCount: 4, percentageOfJobs: 80, rank: 1 },
      { category: 'Backend Developer', jobCount: 1, percentageOfJobs: 20, rank: 2 },
    ]);
  });

  afterEach(cleanup);

  it('renders every insight section from the response', async () => {
    const onShow = renderInsights();

    expect(await screen.findByText('Data Engineer', { selector: 'strong' })).toBeInTheDocument();
    expect(screen.getByText('From your top recommendation')).toBeInTheDocument();

    const section = (name: RegExp) => screen.getByRole('region', { name });
    expect(within(section(/your skills/i)).getByText('Java')).toBeInTheDocument();
    expect(within(section(/strong skills/i)).getByText('Python')).toBeInTheDocument();
    expect(within(section(/strong skills/i)).getByText('75%')).toBeInTheDocument();
    expect(within(section(/skill gaps/i)).getByText('SQL')).toBeInTheDocument();
    expect(within(section(/high-demand/i)).getByText('(on your resume)')).toBeInTheDocument();
    expect(within(section(/trending/i)).getByText('▲ +4.5 pts')).toBeInTheDocument();
    expect(within(section(/focus areas/i)).getByText(/In 50% of Data Engineer postings \(rank 2\)/))
      .toHaveTextContent('rising 4.5 pts market-wide');

    expect(screen.getByRole('link', { name: 'Data Engineer II' })).toHaveAttribute('href', '/jobs/5');
    fireEvent.click(screen.getByRole('button', { name: 'View recommended jobs' }));
    expect(onShow).toHaveBeenCalledTimes(1);

    // No category chosen yet: the backend picks it, and no AI summary is requested.
    expect(careerInsights).toHaveBeenCalledWith(RESUME_ID, undefined, false);
  });

  it('requests the chosen category, and a summary only on demand', async () => {
    renderInsights();
    await screen.findByText('Data Engineer', { selector: 'strong' });

    fireEvent.change(await screen.findByLabelText('Target role'), { target: { value: 'Backend Developer' } });
    await waitFor(() => expect(careerInsights).toHaveBeenLastCalledWith(RESUME_ID, 'Backend Developer', false));

    careerInsights.mockResolvedValue(insights({ summary: 'Python is covered; SQL is a gap.' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Summarise with AI' }));

    expect(await screen.findByText('Python is covered; SQL is a gap.')).toBeInTheDocument();
    expect(careerInsights).toHaveBeenLastCalledWith(RESUME_ID, 'Backend Developer', true);
  });

  it('shows the empty sections honestly', async () => {
    careerInsights.mockResolvedValue(
      insights({
        categorySource: 'REQUESTED',
        resumeSkills: [],
        strongSkills: [],
        trendingSkills: [],
        focusAreas: [],
        skillGaps: [],
        recommendedJobs: [],
      }),
    );
    renderInsights();

    expect(await screen.findByText('No skills were recognised in this resume.')).toBeInTheDocument();
    expect(screen.getByText('None of your skills appear in Data Engineer postings yet.')).toBeInTheDocument();
    expect(screen.getByText('No Data Engineer skill is rising in the snapshot history.')).toBeInTheDocument();
    expect(screen.getByText('None of your top recommendations are Data Engineer roles.')).toBeInTheDocument();
    expect(screen.queryByText('From your top recommendation')).not.toBeInTheDocument();
  });

  it('explains when no target role could be chosen', async () => {
    careerInsights.mockResolvedValue(
      insights({ targetCategory: undefined, categorySource: undefined, note: 'No target category was given' }),
    );
    renderInsights();

    expect(await screen.findByText('No target role yet')).toBeInTheDocument();
    expect(screen.getByText('No target category was given')).toBeInTheDocument();
  });

  it('shows the error with a retry that re-requests', async () => {
    careerInsights.mockRejectedValueOnce(new ApiError(0, 'Cannot reach the API.'));
    renderInsights();

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the API.');
    fireEvent.click(screen.getByRole('button', { name: /try again/i }));

    expect(await screen.findByText('Data Engineer', { selector: 'strong' })).toBeInTheDocument();
    expect(careerInsights).toHaveBeenCalledTimes(2);
  });
});
