import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { ApiError } from '../api/client';
import type { Resume, ResumeMatch, ResumeRecommendation } from '../api/types';
import { ResumeIntelligence } from './ResumeIntelligence';

const uploadResume = vi.fn();
const resumeRecommendations = vi.fn();
const jobs = vi.fn();
const resumeMatch = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      uploadResume: (...args: unknown[]) => uploadResume(...args),
      resumeRecommendations: (...args: unknown[]) => resumeRecommendations(...args),
      jobs: (...args: unknown[]) => jobs(...args),
      resumeMatch: (...args: unknown[]) => resumeMatch(...args),
    },
  };
});

const resume: Resume = {
  id: '90776dbd-97b0-48c9-92f7-51391abf9ea1',
  fileName: 'jane-developer.pdf',
  fileSizeBytes: 2048,
  status: 'COMPLETED',
  skills: [
    { id: 1, name: 'Java', category: 'LANGUAGE' },
    { id: 2, name: 'Spring Boot', category: 'FRAMEWORK' },
  ],
  uploadedAt: '2026-09-23T10:00:00Z',
};

const recommendation: ResumeRecommendation = {
  jobId: 42,
  jobTitle: 'Senior Backend Engineer',
  companyName: 'Acme Systems',
  location: { id: 1, city: 'Austin', state: 'Texas', country: 'United States', displayName: 'Austin, Texas, United States' },
  jobCategory: 'Backend Developer',
  matchPercentage: 66.7,
  matchedSkills: [{ id: 1, name: 'Java', category: 'LANGUAGE' }],
  missingSkills: [{ id: 3, name: 'Docker', category: 'PLATFORM' }],
};

function emptyJobs() {
  return {
    content: [], page: 0, size: 8, totalElements: 0, totalPages: 0, first: true, last: true,
  };
}

function match(): ResumeMatch {
  return {
    resumeId: resume.id,
    jobId: 42,
    jobTitle: recommendation.jobTitle,
    companyName: recommendation.companyName,
    jobCategory: recommendation.jobCategory,
    matchPercentage: recommendation.matchPercentage,
    totalJobSkills: 3,
    totalResumeSkills: 2,
    matchedSkillCount: 1,
    missingSkillCount: 1,
    matchedSkills: recommendation.matchedSkills,
    missingSkills: recommendation.missingSkills,
    resumeOnlySkills: [{ id: 2, name: 'Spring Boot', category: 'FRAMEWORK' }],
  };
}

async function uploadCompletedResume() {
  fireEvent.change(screen.getByLabelText('Resume PDF'), {
    target: { files: [new File(['pdf'], 'jane-developer.pdf', { type: 'application/pdf' })] },
  });
  await screen.findByText('Recommended jobs');
}

describe('ResumeIntelligence recommendations', () => {
  beforeEach(() => {
    uploadResume.mockReset();
    resumeRecommendations.mockReset();
    jobs.mockReset();
    resumeMatch.mockReset();
    uploadResume.mockResolvedValue(resume);
    resumeRecommendations.mockResolvedValue([recommendation]);
    jobs.mockResolvedValue(emptyJobs());
    resumeMatch.mockResolvedValue(match());
  });

  afterEach(cleanup);

  it('shows recommended jobs with the matching skill gap and actions', async () => {
    render(<MemoryRouter><ResumeIntelligence /></MemoryRouter>);

    await uploadCompletedResume();

    expect(await screen.findByText('Senior Backend Engineer')).toBeInTheDocument();
    expect(screen.getByText('67%')).toBeInTheDocument();
    expect(screen.getByText('Acme Systems · Austin, Texas, United States')).toBeInTheDocument();
    expect(screen.getAllByText('Java')).not.toHaveLength(0);
    expect(screen.getByText('Docker')).toBeInTheDocument();
    expect(resumeRecommendations).toHaveBeenCalledWith(resume.id);
    expect(screen.getByRole('link', { name: 'View job' })).toHaveAttribute('href', '/jobs/42');

    fireEvent.click(screen.getByRole('button', { name: 'Compare resume' }));
    await waitFor(() => expect(resumeMatch).toHaveBeenCalledWith(resume.id, 42));
  });

  it('shows a useful empty state when the resume has no overlapping jobs', async () => {
    resumeRecommendations.mockResolvedValue([]);
    render(<MemoryRouter><ResumeIntelligence /></MemoryRouter>);

    await uploadCompletedResume();

    expect(await screen.findByText('No matching jobs yet')).toBeInTheDocument();
  });

  it('shows a user-facing error when recommendations cannot be loaded', async () => {
    resumeRecommendations.mockRejectedValue(new ApiError(0, 'Cannot reach the API.'));
    render(<MemoryRouter><ResumeIntelligence /></MemoryRouter>);

    await uploadCompletedResume();

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the API.');
  });
});
