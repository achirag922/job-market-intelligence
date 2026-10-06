import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { InterviewSession } from '../api/types';
import { InterviewPrep } from './InterviewPrep';

const savedJobs = vi.fn();
const resumes = vi.fn();
const interviewSessions = vi.fn();
const startInterview = vi.fn();
const skipInterviewQuestion = vi.fn();
const completeInterview = vi.fn();
const createLearningItem = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      savedJobs: (...args: unknown[]) => savedJobs(...args),
      resumes: (...args: unknown[]) => resumes(...args),
      interviewSessions: (...args: unknown[]) => interviewSessions(...args),
      startInterview: (...args: unknown[]) => startInterview(...args),
      skipInterviewQuestion: (...args: unknown[]) => skipInterviewQuestion(...args),
      completeInterview: (...args: unknown[]) => completeInterview(...args),
      createLearningItem: (...args: unknown[]) => createLearningItem(...args),
    },
  };
});

const session: InterviewSession = {
  id: 'i1', jobId: 42, jobTitle: 'Backend Developer', companyName: 'Acme', status: 'IN_PROGRESS',
  createdAt: '2026-09-29T10:00:00Z', answered: 0, evaluated: 0, total: 2, interviewType: 'TECHNICAL', difficulty: 'HARD',
  skipped: 0,
  questions: [
    { position: 1, category: 'TECHNICAL', question: 'Describe how you used Kubernetes.', focus: 'Kubernetes', feedbackStatus: 'NOT_ANSWERED', evaluationAttempts: 0 },
    { position: 2, category: 'TECHNICAL', question: 'Describe a problem you solved with Java.', focus: 'Java', feedbackStatus: 'NOT_ANSWERED', evaluationAttempts: 0 },
  ],
};

beforeEach(() => {
  savedJobs.mockResolvedValue([{ id: 's1', job: { id: 42, title: 'Backend Developer', company: { id: 1, name: 'Acme' } }, status: 'SAVED', savedAt: '', updatedAt: '' }]);
  resumes.mockResolvedValue([{ id: 'r1', fileName: 'cv.pdf', title: 'Main CV', status: 'COMPLETED', skills: [], fileSizeBytes: 1, uploadedAt: '' }]);
  interviewSessions.mockResolvedValue([]);
  startInterview.mockResolvedValue(session);
  skipInterviewQuestion.mockResolvedValue({ ...session.questions![0], feedbackStatus: 'SKIPPED', skippedAt: '2026-09-29T10:01:00Z' });
  completeInterview.mockResolvedValue({
    ...session, status: 'COMPLETED', completedAt: '2026-09-29T10:05:00Z', skipped: 1,
    summary: 'You answered 1 of 2 questions; 1 were evaluated, averaging 4.0 out of 5. You skipped 1.',
    questions: [{ ...session.questions![0], feedbackStatus: 'SKIPPED' }, { ...session.questions![1], answer: 'I built a service.' }],
    report: { overallScore: 4, technicalScore: 4, strongAreas: ['Java'], weakAreas: [], prepareTopics: ['Kubernetes'],
      learning: [{ skill: 'Kubernetes', source: 'ROADMAP_PRIORITY', skillId: 3, reason: 'In 33.3% of Backend Developer postings' }] },
  });
  createLearningItem.mockResolvedValue({});
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('InterviewPrep mock interview (V9.6)', () => {
  it('starts with the chosen resume, type, difficulty and question count', async () => {
    render(<InterviewPrep />);
    fireEvent.change(await screen.findByLabelText('Job'), { target: { value: '42' } });
    await screen.findByRole('option', { name: 'Main CV' });
    fireEvent.change(screen.getByLabelText('Resume'), { target: { value: 'r1' } });
    fireEvent.change(screen.getByLabelText('Interview type'), { target: { value: 'TECHNICAL' } });
    fireEvent.change(screen.getByLabelText('Difficulty'), { target: { value: 'HARD' } });
    fireEvent.change(screen.getByLabelText('Questions'), { target: { value: '4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Start interview' }));
    await screen.findByText('Describe how you used Kubernetes.');
    expect(startInterview).toHaveBeenCalledWith({ jobId: 42, resumeId: 'r1', interviewType: 'TECHNICAL', difficulty: 'HARD', questionCount: 4 });
  });

  it('skips a question, ends the interview and shows the report with a learning recommendation', async () => {
    render(<InterviewPrep />);
    fireEvent.change(await screen.findByLabelText('Job'), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: 'Start interview' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Skip question' }));
    expect(await screen.findByText('Describe a problem you solved with Java.')).toBeTruthy();
    expect(skipInterviewQuestion).toHaveBeenCalledWith('i1', 1);
    expect(screen.getByText('1 skipped')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'End interview' }));
    expect(await screen.findByText('Overall 4.0/5')).toBeTruthy();
    expect(screen.getByText(/Technical 4.0\/5 · Behavioral n\/a/)).toBeTruthy();
    expect(screen.getByText('Strong areas: Java')).toBeTruthy();
    expect(screen.getByText('Prepare more: Kubernetes')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add Kubernetes to learning plan' }));
    await waitFor(() => expect(createLearningItem).toHaveBeenCalledWith({ skillId: 3, topic: 'Interview preparation: Kubernetes' }));
    expect(await screen.findByText(/added to your/)).toBeTruthy();
  });
});
