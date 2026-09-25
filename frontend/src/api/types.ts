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
  /** V7.3: the owner's name for this version; starts as the file name. */
  title?: string;
  versionLabel?: string | null;
  /** V7.3: the account's default resume. */
  isDefault?: boolean;
  updatedAt?: string;
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

// ---------------------------------------------------------------- V6.5: ETL monitoring

/** A Spring Batch status collapsed into what the dashboard shows. */
export type EtlRunOutcome = 'SUCCEEDED' | 'FAILED' | 'RUNNING' | 'STOPPED';

export interface EtlRun {
  executionId: number;
  jobName: string;
  outcome: EtlRunOutcome;
  batchStatus: string;
  exitCode?: string;
  exitMessage?: string;
  /** ETL server local time, without a zone. */
  startTime?: string;
  endTime?: string;
  /** Elapsed so far while running; absent when unknown. */
  durationMillis?: number;
  recordsRead: number;
  recordsProcessed: number;
  recordsWritten: number;
  /** Absent while running, or for runs recorded before V6.5. */
  recordsLoaded?: number;
  duplicates?: number;
  rejected: number;
}

// ---------------------------------------------------------------- V6.10.2: accounts

/** A user as the API shows it. There is no password field, and never will be. */
export interface AuthUser {
  id: string;
  email: string;
  role: 'USER';
  /** Absent for accounts created before names were collected. */
  fullName?: string;
  emailVerified: boolean;
  createdAt: string;
}

/** Returned by login and /me. The session itself is an HttpOnly cookie, not in here. */
export interface AuthSession {
  user: AuthUser;
  csrfToken: string;
}

/** Returned by resend: when another code may be requested, and how long codes last. */
export interface VerificationStatus {
  email: string;
  resendAvailableInSeconds: number;
  codeValidForSeconds: number;
}

/** V7.1: how often an alert's owner wants to hear about new matches. Nothing is sent yet. */
export type AlertFrequency = 'DAILY' | 'WEEKLY';

/** What the user edits: the job search's own filters, plus a name and a frequency. */
export interface JobAlertInput {
  name: string;
  keywords?: string;
  category?: string;
  location?: string;
  experience?: string;
  skill?: string;
  frequency: AlertFrequency;
}

export interface JobAlert extends JobAlertInput {
  id: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

/** V7.2: where an application for a saved job stands. */
export type ApplicationStatus = 'SAVED' | 'APPLIED' | 'INTERVIEW' | 'OFFER' | 'REJECTED' | 'WITHDRAWN';

/** A job the signed-in user saved, with their private tracking details. */
export interface SavedJob {
  id: string;
  job: JobSummary;
  status: ApplicationStatus;
  notes?: string | null;
  savedAt: string;
  appliedAt?: string | null;
  updatedAt: string;
}

/** V7.3: one resume against one job: the V3 match plus experience and data-based suggestions. */
export interface ResumeJobAnalysis {
  resumeId: string;
  resumeTitle: string;
  resumeVersionLabel?: string;
  jobId: number;
  jobTitle: string;
  companyName: string;
  jobCategory?: string;
  matchPercentage?: number;
  matchNote?: string;
  totalJobSkills: number;
  matchedSkillCount: number;
  missingSkillCount: number;
  matchedSkills: Skill[];
  missingSkills: Skill[];
  otherResumeSkills: Skill[];
  experience: { required?: { min?: number; max?: number }; note: string };
  suggestions: string[];
  disclaimer: string;
}

export interface ResumeVersionSummary {
  id: string;
  title: string;
  versionLabel?: string;
  fileName: string;
  isDefault: boolean;
  skillCount: number;
  uploadedAt: string;
  updatedAt: string;
}

/** V7.3: two resume versions; "added" and "removed" read from the first to the second. */
export interface ResumeComparison {
  first: ResumeVersionSummary;
  second: ResumeVersionSummary;
  skillsAdded: Skill[];
  skillsRemoved: Skill[];
  commonSkills: Skill[];
  differentFields: string[];
}

/** V7.4 career goals. */
export type CareerGoalStatus = 'ACTIVE' | 'COMPLETED' | 'ARCHIVED';
export type SkillProgressStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED';

export interface CareerGoalInput {
  targetRole: string;
  /** A job category (V4); the roadmap reads skill demand from its postings. */
  targetCategory: string;
  targetLocation?: string;
  targetExperience?: string;
  /** Skill names the user wants to develop, on top of market demand. */
  targetSkills: string[];
}

export interface CareerGoal extends Omit<CareerGoalInput, 'targetSkills'> {
  id: string;
  targetSkills: Skill[];
  status: CareerGoalStatus;
  createdAt: string;
  updatedAt: string;
}

export interface RoadmapSkill {
  priority: number;
  skillId: number;
  skill: string;
  category?: string;
  source: 'MARKET_DEMAND' | 'YOUR_CHOICE';
  percentageOfJobs?: number;
  demandRank?: number;
  trendChangeInPercentagePoints?: number;
  reason: string;
  status: SkillProgressStatus;
}

export interface Roadmap {
  goalId: string;
  targetRole: string;
  targetCategory: string;
  basedOnResume?: { id: string; title: string };
  currentSkills: Skill[];
  marketSkills: {
    skillId: number;
    skill: string;
    jobCount: number;
    percentageOfJobs: number;
    demandRank: number;
    trendChangeInPercentagePoints?: number;
    onResume: boolean;
  }[];
  coveredSkills: Skill[];
  roadmap: RoadmapSkill[];
  progress: {
    totalSkills: number;
    onResume: number;
    completed: number;
    inProgress: number;
    notStarted: number;
    percentComplete?: number;
  };
  staged: boolean;
  note?: string;
}

/** V7.5 market intelligence. Every figure is counted from postings; nothing is estimated. */
export interface MarketFilters {
  category?: string;
  location?: string;
  experience?: string;
  /** The last N posting months of the data (the period ends at the newest posting, not today). */
  months?: number;
}

export interface MarketScope {
  category?: string;
  location?: string;
  experience?: string;
  from?: string;
  latestPostingInData?: string;
  postings: number;
  datedPostings: number;
  earliestMonth?: string;
  latestMonth?: string;
}

export interface SalaryFigure {
  currency: string;
  category?: string;
  postings: number;
  averageMin?: number;
  averageMax?: number;
  lowestMin?: number;
  highestMax?: number;
  reliable: boolean;
}

export interface MarketSalary {
  scope: MarketScope;
  postingsWithSalary: number;
  byCurrency: SalaryFigure[];
  byCategory: SalaryFigure[];
  trend: { month: string; currency: string; postings: number; averageMin?: number; averageMax?: number; reliable: boolean }[];
  notes: string[];
}

export interface MarketLocations {
  scope: MarketScope;
  locationStated: number;
  locationNotStated: number;
  topLocations: { locationId: number; location: string; country: string; postings: number; percentageOfPostings: number }[];
  notes: string[];
}

export type WorkMode = 'REMOTE' | 'HYBRID' | 'ON_SITE' | 'NOT_STATED';

export interface MarketRemote {
  scope: MarketScope;
  distribution: { mode: WorkMode; postings: number; percentageOfPostings: number }[];
  trend: { month: string; total: number; remote: number; hybrid: number; onSite: number; notStated: number }[];
  method: string;
  notes: string[];
}

export interface MarketCompanies {
  scope: MarketScope;
  topCompanies: { companyId: number; company: string; industry?: string; postings: number; percentageOfPostings: number }[];
  trend: { companyId: number; company: string; points: { month: string; postings: number }[] }[];
  notes: string[];
}

export interface MarketSkills {
  scope: MarketScope;
  topSkills: { skillId: number; skill: string; category?: string; postings: number; percentageOfPostings: number; rank: number }[];
  /** From the stored monthly skill history; absent when a filter narrows the view. */
  trend?: SkillTrends;
  notes: string[];
}
