import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { InterviewSession } from '../api/types';
import { InterviewPrep } from './InterviewPrep';

const savedJobs = vi.fn();
const interviewSessions = vi.fn();
const startInterview = vi.fn();
const interviewSession = vi.fn();
const answerInterviewQuestion = vi.fn();
const evaluateInterviewQuestion = vi.fn();
const completeInterview = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      savedJobs: (...args: unknown[]) => savedJobs(...args),
      interviewSessions: (...args: unknown[]) => interviewSessions(...args),
      startInterview: (...args: unknown[]) => startInterview(...args),
      resumes: () => Promise.resolve([]),
      interviewSession: (...args: unknown[]) => interviewSession(...args),
      answerInterviewQuestion: (...args: unknown[]) => answerInterviewQuestion(...args),
      evaluateInterviewQuestion: (...args: unknown[]) => evaluateInterviewQuestion(...args),
      completeInterview: (...args: unknown[]) => completeInterview(...args),
    },
  };
});

const session: InterviewSession = {
  id: 'i1', jobId: 42, jobTitle: 'Backend Developer', companyName: 'Acme', status: 'IN_PROGRESS',
  createdAt: '2026-09-28T10:00:00Z', answered: 0, evaluated: 0, total: 2,
  questions: [
    { position: 1, category: 'TECHNICAL', question: 'Describe a problem you solved with Java.', focus: 'Java', feedbackStatus: 'NOT_ANSWERED', evaluationAttempts: 0 },
    { position: 2, category: 'BEHAVIORAL', question: 'Tell me about a time you learned quickly.', feedbackStatus: 'NOT_ANSWERED', evaluationAttempts: 0 },
  ],
};

beforeEach(() => {
  savedJobs.mockResolvedValue([{ id: 's1', job: { id: 42, title: 'Backend Developer', company: { id: 1, name: 'Acme' } }, status: 'APPLIED', savedAt: '', updatedAt: '' }]);
  interviewSessions.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('InterviewPrep', () => {
  it('starts a session for a saved job, submits an answer and shows the feedback and progress', async () => {
    startInterview.mockResolvedValue(session);
    answerInterviewQuestion.mockResolvedValue({
      ...session.questions![0], answer: 'I used Java to rebuild a service.', feedbackStatus: 'EVALUATED', evaluationAttempts: 1,
      feedback: { relevance: 4, completeness: 3, clarity: 4, technicalCorrectness: 4, score: 3.8,
        strengths: ['Direct answer.'], improvements: ['Add the result you achieved.'] },
    });
    render(<InterviewPrep />);

    fireEvent.change(await screen.findByLabelText('Job'), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: 'Start interview' }));

    expect(await screen.findByText('Describe a problem you solved with Java.')).toBeTruthy();
    expect(startInterview).toHaveBeenCalledWith({ jobId: 42, interviewType: 'MIXED', difficulty: 'MEDIUM' });
    expect(screen.getByText('Question 1 of 2 · 0 answered')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Your answer'), { target: { value: 'I used Java to rebuild a service.' } });
    fireEvent.click(screen.getByRole('button', { name: 'Submit answer' }));

    expect(await screen.findByText('Score 3.8/5')).toBeTruthy();
    expect(screen.getByText('Add the result you achieved.')).toBeTruthy();
    expect(screen.getByText('Question 1 of 2 · 1 answered')).toBeTruthy();
    expect(answerInterviewQuestion).toHaveBeenCalledWith('i1', 1, 'I used Java to rebuild a service.');

    fireEvent.click(screen.getByRole('button', { name: 'Next question' }));
    expect(screen.getByText('Tell me about a time you learned quickly.')).toBeTruthy();
  });

  it('keeps the answer when feedback is unavailable, offers a retry, and lists previous sessions', async () => {
    interviewSessions.mockResolvedValue([{ ...session, questions: undefined, status: 'COMPLETED', answered: 2, averageScore: 3.5 }]);
    interviewSession.mockResolvedValue({ ...session, status: 'IN_PROGRESS' });
    answerInterviewQuestion.mockResolvedValue({
      ...session.questions![0], answer: 'My answer', feedbackStatus: 'UNAVAILABLE', evaluationAttempts: 1,
      feedbackNote: 'AI feedback is not available right now. Your answer is saved; try again later.',
    });
    render(<InterviewPrep />);

    expect(await screen.findByText(/2 of 2 answered · average 3.5\/5/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));

    fireEvent.change(await screen.findByLabelText('Your answer'), { target: { value: 'My answer' } });
    fireEvent.click(screen.getByRole('button', { name: 'Submit answer' }));

    expect(await screen.findByText(/not available right now/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Try feedback again' })).toBeTruthy();
    await waitFor(() => expect(interviewSession).toHaveBeenCalledWith('i1'));
  });
});
