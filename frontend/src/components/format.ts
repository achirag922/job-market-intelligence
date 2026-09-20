import type { Experience, JobSummary, Salary } from '../api/types';

/**
 * Display helpers. Every one of these has to handle absent data, because the API omits
 * fields the source never supplied rather than inventing zeros.
 */

export function formatExperience(experience?: Experience): string {
  if (!experience || (experience.min === undefined && experience.max === undefined)) {
    return 'Not specified';
  }
  if (experience.min !== undefined && experience.max !== undefined) {
    return `${experience.min}–${experience.max} years`;
  }
  if (experience.min !== undefined) {
    return `${experience.min}+ years`;
  }
  return `Up to ${experience.max} years`;
}

export function formatSalary(salary?: Salary): string {
  if (!salary || (salary.min === undefined && salary.max === undefined)) {
    return 'Not disclosed';
  }
  const currency = salary.currency ?? '';
  const amount = (value: number) => value.toLocaleString('en-US', { maximumFractionDigits: 0 });
  if (salary.min !== undefined && salary.max !== undefined) {
    return `${currency} ${amount(salary.min)} – ${amount(salary.max)}`.trim();
  }
  if (salary.min !== undefined) {
    return `${currency} ${amount(salary.min)}+`.trim();
  }
  return `Up to ${currency} ${amount(salary.max as number)}`.trim();
}

/** A posting with no location is remote or unspecified, never a blank cell. */
export function formatLocation(job: Pick<JobSummary, 'location'>): string {
  return job.location?.displayName ?? 'Remote / not specified';
}

export function formatEmploymentType(employmentType?: string): string {
  if (!employmentType) {
    return 'Not specified';
  }
  return employmentType
    .split('_')
    .map((word) => word.charAt(0) + word.slice(1).toLowerCase())
    .join(' ');
}

export function formatDate(isoDate?: string): string {
  return isoDate ?? 'Unknown';
}
