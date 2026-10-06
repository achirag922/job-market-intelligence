import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { InfoTip, PageGuide } from '../components/guidance';
import { ErrorState } from '../components/ui';
import { HelpPage } from './Help';

afterEach(cleanup);

describe('HelpPage', () => {
  it('lists the sections and links each answer to where it is done', () => {
    render(<MemoryRouter><HelpPage /></MemoryRouter>);
    expect(screen.getByRole('heading', { name: 'Getting started' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Troubleshooting' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open account settings' })).toHaveAttribute('href', '/settings#account');
  });

  it('filters answers by the search text and says when nothing matches', () => {
    render(<MemoryRouter><HelpPage /></MemoryRouter>);
    fireEvent.change(screen.getByLabelText('Search help'), { target: { value: 'readiness' } });
    expect(screen.getByText('What is the career readiness score?')).toBeInTheDocument();
    expect(screen.queryByText('How do I delete my account?')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Search help'), { target: { value: 'zzzz' } });
    expect(screen.getByText('No answers found')).toBeInTheDocument();
  });
});

describe('InfoTip', () => {
  it('shows its explanation on focus, describes the button, and hides on Escape', () => {
    render(<InfoTip label="Match">Share of the job's skills on your resume.</InfoTip>);
    const button = screen.getByRole('button', { name: 'About Match' });
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    fireEvent.focus(button);
    const tip = screen.getByRole('tooltip');
    expect(tip).toHaveTextContent("Share of the job's skills");
    expect(button).toHaveAttribute('aria-describedby', tip.id);
    fireEvent.keyDown(button, { key: 'Escape' });
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });
});

describe('PageGuide', () => {
  beforeEach(() => localStorage.clear());

  it('shows once, and stays dismissed after Got it', () => {
    const { unmount } = render(<PageGuide id="jobs" title="Find jobs" helpAnchor="jobs">Filter and sort.</PageGuide>);
    expect(screen.getByRole('complementary', { name: 'Tip: Find jobs' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Learn more in Help' })).toHaveAttribute('href', '/help#jobs');
    fireEvent.click(screen.getByRole('button', { name: 'Got it' }));
    expect(screen.queryByText('Filter and sort.')).not.toBeInTheDocument();
    unmount();
    render(<PageGuide id="jobs" title="Find jobs">Filter and sort.</PageGuide>);
    expect(screen.queryByText('Filter and sort.')).not.toBeInTheDocument();
  });
});

describe('ErrorState', () => {
  it('offers a retry and a way to troubleshooting', () => {
    render(<ErrorState message="Something went wrong on our side." onRetry={() => {}} />);
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Help' })).toHaveAttribute('href', '/help#troubleshooting');
  });
});
