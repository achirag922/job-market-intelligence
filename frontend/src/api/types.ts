/**
 * Mirrors the DTOs the backend returns. Fields the API omits when absent are optional
 * here, which keeps the "unknown salary" and "remote, no location" cases visible to the
 * type checker instead of showing up as "undefined" on screen.
 */

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface Company {
  id: number;
  name: string;
  industry?: string;
  website?: string;
}

export interface CompanyDetail extends Company {
  jobCount: number;
}

export interface Location {
  id: number;
  city?: string;
  state?: string;
  country: string;
  displayName: string;
}

export interface Skill {
  id: number;
  name: string;
  category?: string;
}

export interface Experience {
  min?: number;
  max?: number;
}

export interface Salary {
  min?: number;
  max?: number;
  currency?: string;
}

export interface JobSummary {
  id: number;
  title: string;
  company: Company;
  /** Absent for remote postings. */
  location?: Location;
  employmentType?: string;
  experience?: Experience;
  salary?: Salary;
  postedDate?: string;
  skills: Skill[];
}

export interface JobDetail extends JobSummary {
  description?: string;
  source: string;
  sourceUrl?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface Overview {
  totalJobs: number;
  totalCompanies: number;
  totalSkills: number;
  totalLocations: number;
}

export interface SkillDemand {
  skillId: number;
  skill: string;
  category?: string;
  jobCount: number;
  percentageOfJobs: number;
}

export interface LocationDemand {
  location: Location;
  jobCount: number;
}

export interface CompanyDemand {
  company: Company;
  jobCount: number;
}

/** The error body every failing endpoint returns. */
export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  fieldErrors?: { field: string; message: string }[];
}

export interface JobFilters {
  title?: string;
  location?: string;
  company?: string;
  skill?: string;
  employmentType?: string;
}
