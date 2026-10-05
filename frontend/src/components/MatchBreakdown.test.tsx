import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { MatchBreakdown } from '../api/types';
import { MatchBreakdownList } from './MatchBreakdown';

afterEach(cleanup);

const base: MatchBreakdown = {
  overallPercentage: 61.1,
  skills: { status: 'PARTIAL', score: 50, weight: 60, detail: '1 of 2 required skills on your resume; 0 of 1 nice-to-have' },
  experience: { status: 'UNAVAILABLE', weight: 15, detail: 'Add your years of experience in match preferences' },
  location: { status: 'UNAVAILABLE', weight: 10, detail: 'Add a preferred location' },
  workMode: { status: 'MATCH', score: 100, weight: 10, detail: 'Remote, as you prefer' },
  salary: { status: 'UNAVAILABLE', weight: 5, detail: 'The posting states no salary' },
};

describe('MatchBreakdownList (V9.3)', () => {
  it('shows the career goal, preferred role and skills, missing important and nice-to-have skills', () => {
    render(<MatchBreakdownList breakdown={{
      ...base,
      careerGoal: { status: 'MATCH', score: 100, weight: 10, detail: 'Matches your goal: Backend Developer' },
      role: { status: 'NO_MATCH', score: 0, weight: 5, detail: 'Backend Developer is not one of your preferred roles' },
      preferredSkills: { status: 'MATCH', score: 100, weight: 5, detail: 'Has your preferred skill: Docker' },
      missingRequiredSkills: ['Docker'],
      optionalSkills: ['Kubernetes'],
    }} />);

    expect(screen.getByText('Career goal')).toBeTruthy();
    expect(screen.getByText('Matches your goal: Backend Developer')).toBeTruthy();
    expect(screen.getByText('Preferred role')).toBeTruthy();
    expect(screen.getByText('Has your preferred skill: Docker')).toBeTruthy();
    expect(screen.getByText('Docker')).toBeTruthy();
    expect(screen.getByText('Nice to have (not counted against you): Kubernetes')).toBeTruthy();
  });

  it('still renders a V8.3 breakdown without the new fields', () => {
    render(<MatchBreakdownList breakdown={base} />);
    expect(screen.getByText('Skills')).toBeTruthy();
    expect(screen.queryByText('Career goal')).toBeNull();
    expect(screen.queryByText(/Missing important skills/)).toBeNull();
  });
});
