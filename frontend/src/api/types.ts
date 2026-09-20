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
  /** DERIVED: position in the ranking, 1 being most in demand. */
  rank: number;
  skillId: number;
  skill: string;
  category?: string;
  jobCount: number;
  percentageOfJobs: number;
}

export interface LocationDemand {
  location: Location;
  jobCount: number;
  percentageOfJobs: number;
  rank: number;
}

export interface CompanyDemand {
  company: Company;
  jobCount: number;
  percentageOfJobs: number;
  rank: number;
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

/**
 * Skill demand rows plus the scope they were measured over. The scope matters once
 * filters are applied: a percentage is meaningless without knowing its denominator.
 */
export interface SkillAnalyticsScope {
  totalJobsInScope: number;
  location?: string;
  fromDate?: string;
  toDate?: string;
  title?: string;
}

export interface SkillAnalytics {
  scope: SkillAnalyticsScope;
  skills: PagedResponse<SkillDemand>;
}

export interface SkillAnalyticsFilters {
  location?: string;
  fromDate?: string;
  toDate?: string;
  title?: string;
}

export interface ExperienceBucket {
  bucket: string;
  label: string;
  minYears?: number;
  maxYearsExclusive?: number;
  jobCount: number;
  percentageOfJobs: number;
}

export interface ExperienceDistribution {
  totalJobs: number;
  buckets: ExperienceBucket[];
}

export type TrendDirection = 'RISING' | 'FALLING' | 'STABLE';

export interface TrendPoint {
  period: string;
  jobCount: number;
  totalJobs: number;
  sharePercentage: number;
}

export interface SkillTrend {
  skillId: number;
  skill: string;
  category?: string;
  jobCountInWindow: number;
  earlierSharePercentage: number;
  recentSharePercentage: number;
  /** DERIVED: percentage points, not percent. 10% to 15% is +5 points. */
  changeInPercentagePoints: number;
  direction: TrendDirection;
  series: TrendPoint[];
}

export interface SkillTrendWindow {
  fromPeriod?: string;
  toPeriod?: string;
  periods: number;
  earlierPeriods: string[];
  recentPeriods: string[];
  totalJobsInWindow: number;
  minJobsThreshold: number;
}

export interface SkillTrends {
  window: SkillTrendWindow;
  trends: SkillTrend[];
}
