import type {
  AssistantRequest,
  AssistantResponse,
  ApiErrorBody,
  CompanyDemand,
  CompanyDetail,
  ExperienceDistribution,
  JobDetail,
  JobFilters,
  JobOrder,
  JobSummary,
  Location,
  LocationDemand,
  Overview,
  CategoryDemand,
  EntitySkill,
  PagedResponse,
  Resume,
  ResumeMatch,
  ResumeRecommendation,
  SalaryRange,
  Skill,
  SkillAnalytics,
  SkillAnalyticsFilters,
  SkillDemand,
  SkillTrends,
  TrendDirection,
} from './types';

/**
 * Base URL of the Spring Boot API, from the environment rather than hardcoded so that
 * dev, staging and production differ only by configuration. See .env.example.
 */
const BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/**
 * An API call that failed, carrying the status and the backend's own message so the UI
 * can show something more useful than "something went wrong".
 */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function request<T>(path: string, params?: Record<string, string | number | undefined>): Promise<T> {
  const url = new URL(`${BASE_URL}${path}`);
  Object.entries(params ?? {}).forEach(([key, value]) => {
    // Empty filters are left off entirely rather than sent as blank strings.
    if (value !== undefined && value !== '') {
      url.searchParams.set(key, String(value));
    }
  });

  let response: Response;
  try {
    response = await fetch(url.toString(), { headers: { Accept: 'application/json' } });
  } catch {
    // fetch only rejects on a network-level failure, which here almost always means the
    // backend is not running or CORS refused the request before it was sent.
    throw new ApiError(0, `Cannot reach the API at ${BASE_URL}. Is the backend running?`);
  }

  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = (await response.json()) as ApiErrorBody;
      if (body.message) {
        message = body.message;
      }
    } catch {
      // A non-JSON error body is not worth failing over; the status line will do.
    }
    throw new ApiError(response.status, message);
  }

  return (await response.json()) as T;
}

/**
 * Uploads a resume. Multipart, so the body is FormData and the browser sets its own
 * Content-Type with the boundary — setting it by hand produces a request the server
 * cannot parse.
 */
async function uploadResume(file: File): Promise<Resume> {
  const body = new FormData();
  body.append('file', file);

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}/api/resumes`, { method: 'POST', body });
  } catch {
    throw new ApiError(0, `Cannot reach the API at ${BASE_URL}. Is the backend running?`);
  }

  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const error = (await response.json()) as ApiErrorBody;
      if (error.message) {
        message = error.message;
      }
    } catch {
      // A non-JSON error body is not worth failing over.
    }
    throw new ApiError(response.status, message);
  }

  return (await response.json()) as Resume;
}

/**
 * Ask the assistant a question.
 *
 * POST with a JSON body rather than a query parameter: questions are long, they are not a
 * resource to cache, and they have no business in a URL or a browser history.
 *
 * A question the assistant cannot answer comes back 200 with an explanation — that is a
 * successful response, not an error — so only transport and genuine 4xx/5xx land here.
 */
async function askAssistant(body: AssistantRequest): Promise<AssistantResponse> {
  let response: Response;
  try {
    response = await fetch(`${BASE_URL}/api/assistant/query`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, `Cannot reach the API at ${BASE_URL}. Is the backend running?`);
  }

  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const error = (await response.json()) as ApiErrorBody;
      if (error.message) {
        message = error.message;
      }
    } catch {
      // A non-JSON error body is not worth failing over.
    }
    throw new ApiError(response.status, message);
  }

  return (await response.json()) as AssistantResponse;
}

export const api = {
  /**
   * @param order a named ordering. Takes precedence over `sort`, which is kept only for
   *              the callers that already pass a plain field
   */
  jobs: (filters: JobFilters, page: number, size: number, sort?: string, order?: JobOrder) =>
    request<PagedResponse<JobSummary>>('/api/jobs', { ...filters, page, size, sort, order }),

  /** The currencies salaries are stated in, for the salary filter. */
  salaryCurrencies: () => request<SalaryRange[]>('/api/jobs/salary-currencies'),

  job: (id: number) => request<JobDetail>(`/api/jobs/${id}`),

  skills: (page: number, size: number, name?: string) =>
    request<PagedResponse<Skill>>('/api/skills', { page, size, name }),

  topSkills: (limit: number) => request<SkillDemand[]>('/api/skills/top', { limit }),

  companies: (page: number, size: number, name?: string) =>
    request<PagedResponse<CompanyDetail>>('/api/companies', { page, size, name }),

  company: (id: number) => request<CompanyDetail>(`/api/companies/${id}`),

  locations: (page: number, size: number, country?: string) =>
    request<PagedResponse<Location>>('/api/locations', { page, size, country }),

  overview: () => request<Overview>('/api/analytics/overview'),

  /** Returns the rows and the scope they were measured over; see SkillAnalytics. */
  skillDemand: (filters: SkillAnalyticsFilters, page: number, size: number) =>
    request<SkillAnalytics>('/api/analytics/skills', { ...filters, page, size }),

  experienceDistribution: () => request<ExperienceDistribution>('/api/analytics/experience'),

  jobCategories: () => request<CategoryDemand[]>('/api/analytics/job-categories'),

  // The category travels as a query parameter, not a path segment: names such as
  // "QA / Automation Engineer" contain a slash, and an encoded slash inside a path
  // segment is rejected by the server with a 400 before it reaches any handler.
  categorySkills: (category: string, limit = 10) =>
    request<EntitySkill[]>('/api/analytics/category/skills', { category, limit }),

  categoryLocations: (category: string, limit = 10) =>
    request<LocationDemand[]>('/api/analytics/category/locations', { category, limit }),

  categoryCompanies: (category: string, limit = 10) =>
    request<CompanyDemand[]>('/api/analytics/category/companies', { category, limit }),

  skillTrends: (months: number, direction?: TrendDirection, limit = 20) =>
    request<SkillTrends>('/api/analytics/skills/trends', { months, direction, limit }),

  uploadResume,

  resume: (id: string) => request<Resume>(`/api/resumes/${id}`),

  resumeMatch: (resumeId: string, jobId: number) =>
    request<ResumeMatch>(`/api/resumes/${resumeId}/match/${jobId}`),

  /** Top deterministic skill-overlap matches for a completed resume. */
  resumeRecommendations: (resumeId: string, limit = 10) =>
    request<ResumeRecommendation[]>(`/api/resumes/${resumeId}/recommendations`, { limit }),

  locationDemand: (page: number, size: number) =>
    request<PagedResponse<LocationDemand>>('/api/analytics/locations', { page, size }),

  companyDemand: (page: number, size: number) =>
    request<PagedResponse<CompanyDemand>>('/api/analytics/companies', { page, size }),

  askAssistant,
};

export { BASE_URL };
