import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { Portfolio } from '../api/types';
import { PortfolioPage } from './Portfolio';
import { PublicProfilePage } from './PublicProfile';

const portfolio = vi.fn();
const createPortfolio = vi.fn();
const publishPortfolio = vi.fn();
const changePortfolioSlug = vi.fn();
const portfolioImport = vi.fn();
const publicProfile = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      portfolio: (...args: unknown[]) => portfolio(...args),
      createPortfolio: (...args: unknown[]) => createPortfolio(...args),
      publishPortfolio: (...args: unknown[]) => publishPortfolio(...args),
      changePortfolioSlug: (...args: unknown[]) => changePortfolioSlug(...args),
      portfolioImport: (...args: unknown[]) => portfolioImport(...args),
      publicProfile: (...args: unknown[]) => publicProfile(...args),
      resumes: () => Promise.resolve([]),
      careerGoals: () => Promise.resolve([{ id: 'g1', targetRole: 'Staff Engineer', status: 'ACTIVE' }]),
    },
  };
});

const saved: Portfolio = {
  slug: 'ana-ruiz', displayName: 'Ana Ruiz', visibility: 'PRIVATE', publicPath: '/profile/ana-ruiz',
  content: { headline: 'Backend Engineer', skills: ['Java'], experience: [], education: [], projects: [], certifications: [],
    achievements: [], links: [] },
  sections: { about: true, skills: true, experience: true, education: true, projects: true, certifications: true,
    achievements: true, careerGoals: false, links: true },
  createdAt: '2026-09-29T10:00:00Z', updatedAt: '2026-09-29T10:00:00Z',
};

beforeEach(() => {
  portfolio.mockRejectedValue(new ApiError(404, 'You have no portfolio yet'));
  createPortfolio.mockResolvedValue(saved);
  publishPortfolio.mockResolvedValue({ ...saved, visibility: 'PUBLIC', publishedAt: '2026-09-29T10:05:00Z' });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('PortfolioPage', () => {
  it('imports from a resume, previews live, respects section visibility and creates the portfolio', async () => {
    portfolioImport.mockResolvedValue({
      displayName: 'Ana Ruiz', source: 'BUILDER', learnedSkills: ['Kubernetes'],
      content: { headline: 'Backend Engineer', about: 'I build services.', skills: ['Java'], experience: [], education: [],
        projects: [], certifications: [], achievements: [], links: [] },
    });
    render(<PortfolioPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Import from resume' }));
    expect(await screen.findByRole('heading', { name: 'Ana Ruiz' })).toBeTruthy();
    const preview = () => within(screen.getByRole('article', { name: 'Profile of Ana Ruiz' }));
    expect(preview().getByText('I build services.')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Add Kubernetes' }));
    expect(screen.getByRole('list', { name: 'Skills' }).textContent).toContain('Kubernetes');

    fireEvent.click(screen.getByRole('checkbox', { name: 'About' }));
    expect(preview().queryByText('I build services.')).toBeNull();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Career goals' }));
    expect(screen.getByText('Working towards: Staff Engineer')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Create portfolio' }));
    await waitFor(() => expect(createPortfolio).toHaveBeenCalled());
    const input = createPortfolio.mock.calls[0][0];
    expect(input.content.skills).toEqual(['Java', 'Kubernetes']);
    expect(input.sections.about).toBe(false);
    expect(input.sections.careerGoals).toBe(true);
    expect(await screen.findByText('Public URL:', { exact: false })).toBeTruthy();
  });

  it('publishes, shows the public URL and reports a taken address', async () => {
    portfolio.mockResolvedValue(saved);
    changePortfolioSlug.mockRejectedValue(new ApiError(409, 'That profile address is already taken'));
    render(<PortfolioPage />);
    expect(await screen.findByText('Private')).toBeTruthy();
    expect(screen.getByText(`${window.location.origin}/profile/ana-ruiz`)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Publish' }));
    expect(await screen.findByText('Public')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Open' }).getAttribute('href')).toBe('/profile/ana-ruiz');

    fireEvent.change(screen.getByLabelText('Profile address'), { target: { value: 'taken-name' } });
    fireEvent.click(screen.getByRole('button', { name: 'Change address' }));
    expect(await screen.findByText('That profile address is already taken')).toBeTruthy();
    expect(changePortfolioSlug).toHaveBeenCalledWith('taken-name');
  });
});

describe('PublicProfilePage', () => {
  const at = (path: string) => render(
    <MemoryRouter initialEntries={[path]}>
      <Routes><Route path="/profile/:slug" element={<PublicProfilePage />} /></Routes>
    </MemoryRouter>,
  );

  it('shows a published profile with only the sections it returns', async () => {
    publicProfile.mockResolvedValue({
      displayName: 'Ana Ruiz', headline: 'Backend Engineer', skills: ['Java'],
      links: [{ label: 'GitHub', url: 'https://github.com/example' }], updatedAt: '2026-09-29T10:00:00Z',
    });
    at('/profile/ana-ruiz');
    expect(await screen.findByRole('heading', { name: 'Ana Ruiz' })).toBeTruthy();
    expect(publicProfile).toHaveBeenCalledWith('ana-ruiz');
    expect(screen.getByRole('link', { name: 'GitHub' }).getAttribute('rel')).toContain('noopener');
    expect(screen.queryByRole('heading', { name: 'Experience' })).toBeNull();
  });

  it('says a private or unknown profile is unavailable', async () => {
    publicProfile.mockRejectedValue(new ApiError(404, 'Profile not found'));
    at('/profile/hidden');
    expect(await screen.findByText('This profile does not exist or is not public.')).toBeTruthy();
  });
});
