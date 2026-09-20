import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { AssistantResponse } from '../api/types';
import { AiAssistant } from './AiAssistant';

/**
 * The assistant page.
 *
 * <p>The behaviour worth pinning here is what the page does with an answer it does not
 * fully trust: an ungrounded reply must not be dressed up as a finding, a failed request
 * must say so rather than hang, and the conversation must carry context forward only when
 * there was something worth carrying.
 */

const askAssistant = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      askAssistant: (...args: unknown[]) => askAssistant(...args),
      uploadResume: vi.fn(),
    },
  };
});

function reply(overrides: Partial<AssistantResponse> = {}): AssistantResponse {
  return {
    question: 'top skills',
    answer: 'Java appears most often in this dataset.',
    intent: 'SKILL_DEMAND',
    grounded: true,
    data: [{ skill: 'Java', jobCount: 34, percentageOfJobs: 24.6 }],
    visualization: {
      type: 'BAR',
      title: 'Top skills',
      xAxis: 'Skill',
      yAxis: 'Job count',
      points: [{ label: 'Java', value: 34 }],
    },
    context: { previousQuestion: 'top skills', previousIntent: 'SKILL_DEMAND' },
    ...overrides,
  };
}

async function ask(question: string) {
  fireEvent.change(screen.getByLabelText('Your question'), { target: { value: question } });
  fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
}

describe('AiAssistant', () => {
  beforeEach(() => {
    askAssistant.mockReset();
  });

  afterEach(cleanup);

  it('submits a question and shows the answer', async () => {
    askAssistant.mockResolvedValue(reply());
    render(<AiAssistant />);

    await ask('top skills');

    expect(await screen.findByText('Java appears most often in this dataset.')).toBeInTheDocument();
    expect(askAssistant).toHaveBeenCalledWith(
      expect.objectContaining({ question: 'top skills' }),
    );
  });

  it('shows a loading state while the answer is pending', async () => {
    let release: (value: AssistantResponse) => void = () => {};
    askAssistant.mockReturnValue(new Promise<AssistantResponse>((resolve) => {
      release = resolve;
    }));
    render(<AiAssistant />);

    await ask('top skills');

    expect(await screen.findByText('Looking this up in the dataset…')).toBeInTheDocument();

    release(reply());
    await waitFor(() =>
      expect(screen.queryByText('Looking this up in the dataset…')).not.toBeInTheDocument(),
    );
  });

  it('renders the question back with the answer', async () => {
    askAssistant.mockResolvedValue(reply());
    render(<AiAssistant />);

    await ask('top skills');

    expect(await screen.findByText(/You asked:/)).toBeInTheDocument();
    expect(screen.getByText(/top skills/)).toBeInTheDocument();
  });

  it('renders a chart when the backend asks for one', async () => {
    askAssistant.mockResolvedValue(reply());
    const { container } = render(<AiAssistant />);

    await ask('top skills');
    await screen.findByText('Java appears most often in this dataset.');

    expect(screen.getByText('Top skills')).toBeInTheDocument();
    await waitFor(() => expect(container.querySelector('svg')).toBeTruthy());
  });

  it('renders a table of rows when the visualization is TABLE', async () => {
    askAssistant.mockResolvedValue(reply({
      answer: 'Two postings match.',
      intent: 'JOB_SEARCH',
      data: [
        { id: 1, title: 'Backend Engineer', company: { name: 'Acme Systems' } },
        { id: 2, title: 'Java Developer', company: { name: 'Globex Data' } },
      ],
      visualization: { type: 'TABLE', title: 'Matching job postings', points: [] },
    }));
    render(<AiAssistant />);

    await ask('show me java jobs');

    expect(await screen.findByText('Backend Engineer')).toBeInTheDocument();
    // A nested DTO is shown by its name, never as [object Object].
    expect(screen.getByText('Acme Systems')).toBeInTheDocument();
  });

  it('draws nothing for a NONE visualization', async () => {
    askAssistant.mockResolvedValue(reply({
      visualization: { type: 'NONE', points: [] },
      data: [{ totalJobs: 138 }],
    }));
    const { container } = render(<AiAssistant />);

    await ask('tell me about this dataset');
    await screen.findByText('Java appears most often in this dataset.');

    expect(container.querySelector('svg')).toBeNull();
  });

  it('says plainly when an answer is not backed by data', async () => {
    // An ungrounded reply is the assistant talking about itself. Showing an empty table
    // would read as "we looked and found nothing", which is a different claim.
    askAssistant.mockResolvedValue(reply({
      answer: 'I could not find the skill "Cobol" in this dataset.',
      grounded: false,
      data: [],
      visualization: { type: 'NONE', points: [] },
    }));
    render(<AiAssistant />);

    await ask('cobol trend');

    expect(await screen.findByText(/could not find the skill/)).toBeInTheDocument();
    expect(screen.getByText('No data was retrieved for this question.')).toBeInTheDocument();
  });

  it('shows a note when the backend attaches one', async () => {
    askAssistant.mockResolvedValue(reply({
      note: 'Figures are grouped by currency and never combined.',
    }));
    render(<AiAssistant />);

    await ask('what do these pay');

    expect(await screen.findByText(/never combined/)).toBeInTheDocument();
  });

  it('shows an error when the request fails, without losing the question', async () => {
    askAssistant.mockRejectedValue(new ApiError(0, 'Cannot reach the API at http://localhost:8080.'));
    render(<AiAssistant />);

    await ask('top skills');

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the API');
    expect(screen.getByText(/You asked:/)).toBeInTheDocument();
  });

  it('sends the previous turn as context with the next question', async () => {
    askAssistant.mockResolvedValue(reply());
    render(<AiAssistant />);

    await ask('top skills for backend developers');
    await screen.findByText('Java appears most often in this dataset.');

    await ask('what about Bengaluru?');

    await waitFor(() => expect(askAssistant).toHaveBeenCalledTimes(2));
    expect(askAssistant.mock.calls[1][0]).toMatchObject({
      question: 'what about Bengaluru?',
      context: { previousIntent: 'SKILL_DEMAND' },
    });
  });

  it('does not carry forward context from a question it could not answer', async () => {
    // Attaching the next question to a filter that never applied would be worse than
    // starting fresh.
    askAssistant.mockResolvedValue(reply({
      grounded: false,
      data: [],
      visualization: { type: 'NONE', points: [] },
    }));
    render(<AiAssistant />);

    await ask('something unanswerable');
    await screen.findByText('No data was retrieved for this question.');

    await ask('and now this');

    await waitFor(() => expect(askAssistant).toHaveBeenCalledTimes(2));
    expect(askAssistant.mock.calls[1][0].context).toBeUndefined();
  });

  it('clears the conversation on request', async () => {
    askAssistant.mockResolvedValue(reply());
    render(<AiAssistant />);

    await ask('top skills');
    await screen.findByText('Java appears most often in this dataset.');

    fireEvent.click(screen.getByRole('button', { name: 'New conversation' }));

    expect(screen.queryByText('Java appears most often in this dataset.')).not.toBeInTheDocument();
  });

  it('will not submit an empty question', async () => {
    render(<AiAssistant />);

    expect(screen.getByRole('button', { name: 'Ask' })).toBeDisabled();
    expect(askAssistant).not.toHaveBeenCalled();
  });

  it('offers example questions and asks one when clicked', async () => {
    askAssistant.mockResolvedValue(reply());
    render(<AiAssistant />);

    fireEvent.click(screen.getByRole('button', { name: 'Which cities have the most Java jobs?' }));

    await waitFor(() => expect(askAssistant).toHaveBeenCalledWith(
      expect.objectContaining({ question: 'Which cities have the most Java jobs?' }),
    ));
  });
});
