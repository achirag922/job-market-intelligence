import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ResumeOptimization } from '../api/types';
import { ResumeOptimizer } from './ResumeOptimizer';

const optimizeResume = vi.fn();
const resumes = vi.fn();
const compareResumesForJob = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      optimizeResume: (...args: unknown[]) => optimizeResume(...args),
      resumes: (...args: unknown[]) => resumes(...args),
      compareResumesForJob: (...args: unknown[]) => compareResumesForJob(...args),
    },
  };
});

const result: ResumeOptimization = {
  resumeId: 'r1', jobId: 1, jobTitle: 'Senior Backend Engineer', companyName: 'Acme',
  overallMatchPercentage: 33.3, skillMatchPercentage: 33.3,
  matchedSkills: [{ id: 1, name: 'Java' }], missingSkills: [{ id: 2, name: 'Docker' }], otherResumeSkills: [],
  experienceGap: 'The posting asks for 5+ years.',
  presentKeywords: [{ term: 'microservices', jobMentions: 3, resumeMentions: 1 }],
  missingKeywords: [{ term: 'senior', jobMentions: 1, resumeMentions: 0 }],
  overusedKeywords: [],
  sectionsFound: ['Summary', 'Skills'], sectionsMissing: ['Experience'],
  suggestions: [{ area: 'KEYWORDS', text: 'The posting uses "senior" (1×) and your resume does not.' }],
  disclaimer: 'JMIP does not rewrite your resume.',
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ResumeOptimizer', () => {
  it('shows the match, skills, keywords, sections and suggestions on request', async () => {
    optimizeResume.mockResolvedValue(result);
    resumes.mockResolvedValue([]);
    render(<ResumeOptimizer resumeId="r1" jobId={1} />);

    fireEvent.click(screen.getByRole('button', { name: 'Optimize for this job' }));

    expect(await screen.findByText('1 of 2')).toBeTruthy();
    expect(optimizeResume).toHaveBeenCalledWith('r1', 1);
    expect(screen.getByText('microservices · job 3× · resume 1×')).toBeTruthy();
    expect(screen.getByText('senior · job 1× · resume 0×')).toBeTruthy();
    expect(screen.getByText(/Sections found: Summary, Skills · Not found: Experience/)).toBeTruthy();
    expect(screen.getByText(/The posting uses "senior"/)).toBeTruthy();
    expect(screen.getByText('JMIP does not rewrite your resume.')).toBeTruthy();
    expect(await screen.findByText(/Upload another version/)).toBeTruthy();
  });

  it('marks keywords unavailable without stored text, and compares versions for the job', async () => {
    optimizeResume.mockResolvedValue({ ...result, presentKeywords: undefined, missingKeywords: undefined, sectionsFound: undefined,
      keywordNote: "This resume's text is not stored." });
    resumes.mockResolvedValue([{ id: 'r2', fileName: 'cv2.pdf', title: 'CV', versionLabel: 'v2', status: 'COMPLETED' }]);
    compareResumesForJob.mockResolvedValue({
      versions: { skillsAdded: [{ id: 2, name: 'Docker' }], skillsRemoved: [], commonSkills: [], differentFields: [] },
      jobId: 1, jobTitle: 'Senior Backend Engineer',
      first: { overallMatchPercentage: 33.3, skillMatchPercentage: 33.3, matchedSkillCount: 1, missingSkillCount: 2 },
      second: { overallMatchPercentage: 66.7, skillMatchPercentage: 66.7, matchedSkillCount: 2, missingSkillCount: 1 },
      overallChange: 33.4, skillChange: 33.4, keywordsGained: ['senior'], keywordsLost: [],
    });
    render(<ResumeOptimizer resumeId="r1" jobId={1} />);

    fireEvent.click(screen.getByRole('button', { name: 'Optimize for this job' }));
    expect(await screen.findByText("This resume's text is not stored.")).toBeTruthy();

    fireEvent.change(await screen.findByLabelText('Version'), { target: { value: 'r2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Compare for this job' }));

    expect(await screen.findByText(/33% → 67% \(\+33\.4 points\)/)).toBeTruthy();
    expect(screen.getByText(/Skills added: Docker/)).toBeTruthy();
    expect(screen.getByText(/Posting terms gained: senior/)).toBeTruthy();
    expect(compareResumesForJob).toHaveBeenCalledWith('r1', 'r2', 1);
  });
});
