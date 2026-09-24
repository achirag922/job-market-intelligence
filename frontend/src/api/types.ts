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
  /** V4: rule-based category, absent until the posting has been classified. */
  category?: string;
  skills: Skill[];
}

export interface JobDetail extends JobSummary {
  /** V4: the category and the evidence behind it. */
  classification?: JobClassification;
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

/**
 * Job search filters, exactly as the API takes them.
 *
 * `title` is the pre-V6.2 search and is kept so old links still work; the search box now
 * writes `q`, which matches across title, company, location, description and skills.
 */
export interface JobFilters {
  q?: string;
  category?: string;
  title?: string;
  location?: string;
  company?: string;
  skill?: string;
  employmentType?: string;
  /** An experience band: 0-2, 2-5, 5-8, 8+ or unspecified. */
  experience?: string;
  /** Scopes the salary bounds; the API refuses bounds without it. */
  currency?: string;
  salaryMin?: string;
  salaryMax?: string;
  /**
   * "true" for postings naming a place, "false" for those that do not. Not a remote
   * filter: the dataset records "remote" and "unspecified" the same way.
   */
  locationStated?: string;
}

/** The named orderings the job search accepts. */
export type JobOrder =
  | 'newest'
  | 'oldest'
  | 'relevance'
  | 'salary-high'
  | 'salary-low'
  | 'title'
  | 'company';

/** One currency salaries are stated in, and the range seen in it. */
export interface SalaryRange {
  currency: string;
  jobCount: number;
  lowestMin: number;
  highestMax?: number;
  averageMin?: number;
  averageMax?: number;
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

export type ResumeStatus = 'UPLOADED' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface Resume {
  id: string;
  fileName: string;
  fileSizeBytes: number;
  status: ResumeStatus;
  skills: Skill[];
  /** Present only when the status is FAILED. */
  errorMessage?: string;
  uploadedAt: string;
  processedAt?: string;
}

export interface ResumeMatch {
  resumeId: string;
  jobId: number;
  jobTitle: string;
  companyName: string;
  /** V4 */
  jobCategory?: string;
  /** Absent when the job lists no skills; matchNote then says why. */
  matchPercentage?: number;
  matchNote?: string;
  totalJobSkills: number;
  totalResumeSkills: number;
  matchedSkillCount: number;
  missingSkillCount: number;
  matchedSkills: Skill[];
  /** The skill gap: what this job wants that the resume does not show. */
  missingSkills: Skill[];
  resumeOnlySkills: Skill[];
}

/** A job whose existing required skills overlap with a completed resume. */
export interface ResumeRecommendation {
  jobId: number;
  jobTitle: string;
  companyName: string;
  /** Absent for a remote or otherwise unspecified posting location. */
  location?: Location;
  jobCategory?: string;
  /** V3's deterministic skills-overlap measure, not hiring likelihood. */
  matchPercentage: number;
  matchedSkills: Skill[];
  missingSkills: Skill[];
}

/** A category skill, with its V4 demand figures and whether the resume shows it. */
export interface InsightDemandSkill {
  skillId: number;
  skill: string;
  category?: string;
  jobCount: number;
  /** PERCENTAGE: share of the target category's postings. */
  percentageOfJobs: number;
  rank: number;
  onResume: boolean;
}

/** A category skill whose market-wide share of postings is rising (V4 trends). */
export interface InsightTrendingSkill {
  skillId: number;
  skill: string;
  category?: string;
  earlierSharePercentage: number;
  recentSharePercentage: number;
  changeInPercentagePoints: number;
  onResume: boolean;
}

export interface InsightFocusArea {
  skillId: number;
  skill: string;
  percentageOfJobs: number;
  demandRank: number;
  /** Present only when the skill is rising market-wide. */
  changeInPercentagePoints?: number;
}

export interface CareerInsights {
  resumeId: string;
  /** Absent when no category was given and no recommendation suggested one. */
  targetCategory?: string;
  categorySource?: 'REQUESTED' | 'TOP_RECOMMENDATION';
  resumeSkills: Skill[];
  highDemandSkills: InsightDemandSkill[];
  strongSkills: InsightDemandSkill[];
  skillGaps: InsightDemandSkill[];
  trendingSkills: InsightTrendingSkill[];
  focusAreas: InsightFocusArea[];
  recommendedJobs: ResumeRecommendation[];
  /** V5 AI description of the figures, only when requested and available. */
  summary?: string;
  note?: string;
}

// ---------------------------------------------------------------- V4: job intelligence

export interface CategoryDemand {
  category: string;
  jobCount: number;
  /** PERCENTAGE: share of the classified postings, not of every posting. */
  percentageOfJobs: number;
  rank: number;
}

export interface ClassificationSignal {
  /** TITLE, DESCRIPTION or SKILL — where the match came from. */
  type: string;
  value: string;
  weight: number;
}

export interface JobClassification {
  category: string;
  /** DERIVED: strength and clarity of the evidence, 0-100. Not a probability. */
  confidence?: number;
  signals: ClassificationSignal[];
}

/**
 * A skill in demand within one company, location or category.
 *
 * <p>percentageOfJobs is a share of that entity's own postings, never of all postings.
 */
export interface EntitySkill {
  skillId: number;
  skill: string;
  category?: string;
  jobCount: number;
  percentageOfJobs: number;
  rank: number;
}

// ------------------------------------------------------------------ V5 assistant

/** The chart shapes the backend may ask for. Anything else is not rendered. */
export type VisualizationType = 'BAR' | 'LINE' | 'PIE' | 'TABLE' | 'NONE';

export interface ChartPoint {
  label: string;
  value: number;
}

/**
 * How to draw an answer.
 *
 * Metadata only — a known type, labels and numbers. The backend never sends markup or
 * code, and the frontend maps `type` to a component it already has rather than
 * interpreting anything.
 */
export interface Visualization {
  type: VisualizationType;
  title?: string;
  xAxis?: string;
  yAxis?: string;
  points: ChartPoint[];
}

/** The entities a question was about, carried between turns. */
export interface AssistantEntities {
  skill?: string;
  secondSkill?: string;
  jobCategory?: string;
  secondJobCategory?: string;
  company?: string;
  location?: string;
  title?: string;
}

/**
 * The previous turn, echoed back with the next question.
 *
 * The server keeps no session state; this is what makes "what about Bengaluru?" resolve
 * against the question before it.
 */
export interface ConversationContext {
  previousQuestion?: string;
  previousIntent?: string;
  previousEntities?: AssistantEntities;
}

export interface AssistantRequest {
  question: string;
  resumeId?: string;
  jobId?: number;
  context?: ConversationContext;
}

/**
 * An assistant reply.
 *
 * `grounded` says whether `data` came from a database query. When it is false the answer
 * is the assistant talking about itself — an unsupported question, a missing resume, an
 * unavailable provider — and carries no claim about the job market.
 */
export interface AssistantResponse {
  question: string;
  answer: string;
  intent: string;
  grounded: boolean;
  data: unknown[];
  visualization: Visualization;
  context?: ConversationContext;
  note?: string;
}
