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
  /** V9.2: fill empty filters from the signed-in user's preferences (explicit filters win). */
  usePreferences?: 'true';
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
  /** V9.4: UPLOAD for a PDF, BUILDER for one written in the Resume Builder. */
  source?: 'UPLOAD' | 'BUILDER';
}

// ---------------------------------------------------------------- V9.4: resume builder

/** A built resume's sections; only what the user wrote. */
export interface BuilderContent {
  template?: 'CLASSIC' | 'MODERN';
  personal: { fullName: string; headline?: string; email?: string; phone?: string; location?: string; links?: string[] };
  summary?: string;
  skills?: string[];
  experience?: { title: string; company: string; location?: string; start?: string; end?: string; current: boolean; bullets?: string[] }[];
  education?: { degree: string; institution: string; location?: string; start?: string; end?: string; details?: string }[];
  projects?: { name: string; url?: string; description?: string; bullets?: string[] }[];
  certifications?: { name: string; issuer?: string; date?: string }[];
  achievements?: string[];
  additional?: { title: string; items?: string[] }[];
}

export interface BuiltResume {
  resume: Resume;
  content: BuilderContent;
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
  /** V8.3: overall score and per-dimension breakdown. */
  breakdown?: MatchBreakdown;
}

// ---------------------------------------------------------------- V8.3: match breakdown

export type MatchStatus = 'MATCH' | 'PARTIAL' | 'NO_MATCH' | 'UNAVAILABLE';

export interface MatchDimension {
  status: MatchStatus;
  /** 0 to 100; absent when unavailable. */
  score?: number;
  /** Share of the overall score when available. */
  weight: number;
  detail: string;
}

/** Deterministic compatibility, not a hiring prediction. */
export interface MatchBreakdown {
  /** Absent when the posting lists no skills. */
  overallPercentage?: number;
  skills: MatchDimension;
  experience: MatchDimension;
  location: MatchDimension;
  workMode: MatchDimension;
  salary: MatchDimension;
  /** V9.3: career-goal alignment and the V9.2 preferred roles and skills. */
  careerGoal?: MatchDimension;
  role?: MatchDimension;
  preferredSkills?: MatchDimension;
  /** Required skills the resume does not show. */
  missingRequiredSkills?: string[];
  /** Skills the posting lists only as nice to have; they never lower the skill score. */
  optionalSkills?: string[];
  /** Each available dimension's contribution, in words. */
  reasons?: string[];
}

/** The signed-in user's own match preferences; every field optional. */
export interface MatchPreferences {
  yearsExperience?: number;
  preferredLocation?: string;
  workMode?: 'REMOTE' | 'HYBRID' | 'ON_SITE';
  minSalary?: number;
  salaryCurrency?: string;
  /** V9.2: personalization lists (at most 20 each). */
  preferredCategories?: string[];
  preferredSkills?: string[];
  excludedCompanies?: string[];
  excludedLocations?: string[];
}

// ---------------------------------------------------------------- V9.2: personalized feed

export interface PersonalizedFeedItem {
  job: JobSummary;
  /** The V8.3 overall match; absent without a processed resume. */
  matchPercentage?: number;
  /** The match plus the personal signals in reasons; what the feed is ordered by. */
  priority: number;
  saved: boolean;
  reasons: { text: string; kind: 'POSITIVE' | 'NEGATIVE' | 'INFO'; points?: number }[];
  breakdown?: MatchBreakdown;
}

export interface PersonalizedFeed {
  jobs: PersonalizedFeedItem[];
  context: { resume: boolean; careerGoal?: string; preferredRoles: string[]; preferredSkills: string[];
    candidates: number; excludedApplied: number; excludedByPreference: number };
  note?: string;
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
  /** V8.3: what the list is ranked by; equals matchPercentage without preferences. */
  overallMatchPercentage?: number;
  breakdown?: MatchBreakdown;
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

// ---------------------------------------------------------------- V8.9: admin

export interface AdminOverview {
  users: { total: number; verified: number; admins: number; newLast30Days: number; activeLast30Days: number; activeDefinition: string };
  jobs: { total: number; active: number; inactive: number; firstSeenLast7Days: number; firstSeenLast30Days: number; latestPostedDate?: string };
  etl: { latest?: EtlRun; recent: EtlRun[]; failedLast30Days: number };
  sources: JobSource[];
  health: { status: string; components: Record<string, string> };
  activity: { days: number; signups: number; resumesUploaded: number; jobsSaved: number; applications: number; interviewSessions: number; jobsEmailedInAlerts: number };
}

export interface AdminDataQuality {
  totals: { ingestionRuns: number; recordsRead: number; validRecords: number; rejected: number; loaded: number; duplicates: number; expired: number };
  current: { jobs: number; active: number; inactive: number; expiredByDate: number; closedBySource: number };
  topRejectionReasons: { reason: string; count: number }[];
  sources: { sourceId: number; code: string; name: string; sourceType: string; active: boolean; lastIngestedAt?: string; jobs: number; activeJobs: number; inactiveJobs: number; loaded: number; seenAgain: number }[];
  notes: string[];
}

/** Account metadata only; there is no password, session or code field. */
export interface AdminUserSummary {
  id: string;
  email: string;
  fullName?: string;
  role: "USER" | "ADMIN";
  emailVerified: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AdminUserDetail {
  account: AdminUserSummary;
  resumes: number;
  savedJobs: number;
  applications: number;
  jobAlerts: number;
  interviewSessions: number;
  careerGoals: number;
  lastActivityAt?: string;
  note?: string;
}

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
  /** V8.1: the feed's file name (never its path) and format; absent for older runs. */
  feedName?: string;
  feedType?: string;
  /** V8.1: what the run did with each source it met. */
  sources?: EtlRunSource[];
  /** V8.2: jobs the run marked inactive (expired, or closed by their source). */
  expired?: number;
  /** V9.1: the connector the run read through ("file", "sample", ...). */
  connector?: string;
}

export interface EtlRunSource {
  sourceId: number;
  code: string;
  name: string;
  recordsLoaded: number;
  recordsSeenAgain: number;
}

// ---------------------------------------------------------------- V8.1: job sources

export type JobSourceType = 'FILE_JSON' | 'FILE_CSV' | 'API' | 'OTHER';

/** Where postings come from. Read-only: the ETL registers sources. */
export interface JobSource {
  id: number;
  code: string;
  name: string;
  sourceType: JobSourceType;
  active: boolean;
  createdAt: string;
  lastIngestedAt?: string;
  lastRunExecutionId?: number;
  jobCount: number;
  /** Only on the detail view. */
  recentRuns?: JobSourceRun[];
}

export interface JobSourceRun {
  executionId: number;
  batchStatus: string;
  startTime?: string;
  endTime?: string;
  feedName?: string;
  recordsLoaded: number;
  recordsSeenAgain: number;
}

// ---------------------------------------------------------------- V6.10.2: accounts

/** A user as the API shows it. There is no password field, and never will be. */
export interface AuthUser {
  id: string;
  email: string;
  /** V8.9: ADMIN accounts also have every USER permission. Decided by the server, never by the client. */
  role: 'USER' | 'ADMIN';
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
  /** V8.4: when the digest pass last checked this alert. */
  lastProcessedAt?: string;
}

/** V8.4: a job an alert recorded, and whether its digest email went out. */
export interface JobAlertNotification {
  jobId: number;
  jobTitle: string;
  companyName: string;
  /** V8.3 overall match with the current resume; absent without one. */
  matchPercentage?: number;
  status: 'PENDING' | 'SENT' | 'FAILED';
  recordedAt: string;
  sentAt?: string;
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
  /** V8.5: follow-up date (YYYY-MM-DD) and reminder. */
  followUpOn?: string | null;
  followUpNote?: string | null;
}

// ---------------------------------------------------------------- V8.5: application intelligence

/** One tracked job with the V8.3 match of the current resume; match fields absent without one. */
export interface ApplicationAnalysis {
  id: string;
  job: JobSummary;
  status: ApplicationStatus;
  savedAt: string;
  appliedAt?: string;
  notes?: string;
  followUpOn?: string;
  followUpNote?: string;
  overallMatchPercentage?: number;
  skillMatchPercentage?: number;
  matchedSkills?: Skill[];
  missingSkills?: Skill[];
  matchNote?: string;
}

export interface ApplicationInsightsCount {
  name: string;
  count: number;
}

export interface ApplicationFollowUp {
  id: string;
  jobId: number;
  jobTitle: string;
  companyName: string;
  status: ApplicationStatus;
  followUpOn: string;
  note?: string;
}

/** Facts from the user's own tracked jobs; a figure without enough data is absent, with a note. */
export interface ApplicationInsights {
  tracked: number;
  applications: number;
  statusCounts: Record<ApplicationStatus, number>;
  funnel: {
    applied: number;
    interviewed: number;
    offers: number;
    interviewRate?: number;
    offerRate?: number;
    minimumForRates: number;
    note?: string;
  };
  activity: { month: string; saved: number; applied: number; interviews: number; offers: number }[];
  activityNote?: string;
  averageMatchPercentage?: number;
  scoredApplications: number;
  matchNote?: string;
  topMissingSkills: ApplicationInsightsCount[];
  topCompanies: ApplicationInsightsCount[];
  topRoles: ApplicationInsightsCount[];
  upcomingFollowUps: ApplicationFollowUp[];
  overdueFollowUps: ApplicationFollowUp[];
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
// ---------------------------------------------------------------- V8.6: resume optimization

export interface ResumeKeyword {
  term: string;
  jobMentions: number;
  resumeMentions: number;
}

/** One resume against one job, from the resume and posting only; keyword fields absent without stored text. */
export interface ResumeOptimization {
  resumeId: string;
  resumeTitle?: string;
  resumeVersionLabel?: string;
  jobId: number;
  jobTitle: string;
  companyName: string;
  overallMatchPercentage?: number;
  skillMatchPercentage?: number;
  breakdown?: MatchBreakdown;
  matchedSkills: Skill[];
  missingSkills: Skill[];
  otherResumeSkills: Skill[];
  requiredExperience?: unknown;
  experienceGap: string;
  presentKeywords?: ResumeKeyword[];
  missingKeywords?: ResumeKeyword[];
  overusedKeywords?: ResumeKeyword[];
  keywordNote?: string;
  sectionsFound?: string[];
  sectionsMissing?: string[];
  suggestions: { area: string; text: string }[];
  disclaimer: string;
}

export interface ResumeJobComparisonScore {
  overallMatchPercentage?: number;
  skillMatchPercentage?: number;
  matchedSkillCount: number;
  missingSkillCount: number;
}

export interface ResumeJobComparison {
  versions: ResumeComparison;
  jobId: number;
  jobTitle: string;
  first: ResumeJobComparisonScore;
  second: ResumeJobComparisonScore;
  overallChange?: number;
  skillChange?: number;
  keywordsGained?: string[];
  keywordsLost?: string[];
}

// ---------------------------------------------------------------- V8.7: interview preparation

export interface InterviewFeedback {
  relevance: number;
  completeness: number;
  clarity: number;
  technicalCorrectness?: number;
  score: number;
  strengths: string[];
  improvements: string[];
  evaluatedAt?: string;
  /** V9.6: 1 to 5, absent on answers evaluated before V9.6. */
  communication?: number;
  /** V9.6: how a stronger answer would be built; never written as the user's experience. */
  suggestedApproach?: string;
}

export interface InterviewQuestion {
  position: number;
  category: "TECHNICAL" | "ROLE" | "RESUME" | "BEHAVIORAL";
  question: string;
  focus?: string;
  answer?: string;
  answeredAt?: string;
  feedbackStatus: "NOT_ANSWERED" | "EVALUATED" | "UNAVAILABLE" | "SKIPPED";
  feedback?: InterviewFeedback;
  feedbackNote?: string;
  evaluationAttempts: number;
  skippedAt?: string;
}

/** A practice session; questions only on the detail view. */
export interface InterviewSession {
  id: string;
  jobId?: number;
  jobTitle: string;
  companyName: string;
  resumeId?: string;
  status: "IN_PROGRESS" | "COMPLETED";
  createdAt: string;
  completedAt?: string;
  summary?: string;
  averageScore?: number;
  answered: number;
  evaluated: number;
  total: number;
  questions?: InterviewQuestion[];
  /** V9.6 setup. */
  interviewType?: InterviewType;
  difficulty?: InterviewDifficulty;
  skipped?: number;
  /** V9.6: present on a completed session's detail view. */
  report?: InterviewReport;
}

export type InterviewType = 'TECHNICAL' | 'BEHAVIORAL' | 'MIXED';
export type InterviewDifficulty = 'EASY' | 'MEDIUM' | 'HARD';

export interface InterviewSetup {
  jobId: number;
  resumeId?: string;
  interviewType?: InterviewType;
  difficulty?: InterviewDifficulty;
  questionCount?: number;
}

/** V9.6: the end-of-interview report; scores are 1 to 5. */
export interface InterviewReport {
  overallScore?: number;
  technicalScore?: number;
  behavioralScore?: number;
  strongAreas: string[];
  weakAreas: string[];
  prepareTopics: string[];
  learning: {
    skill: string;
    source: 'PLAN_ITEM' | 'ROADMAP_PRIORITY';
    itemId?: string;
    skillId?: number;
    status?: string;
    reason?: string;
  }[];
}

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
// ---------------------------------------------------------------- V8.8: career market trends

/** A comparison of the earlier and recent halves of the covered months. */
export interface MarketTrend {
  direction: "INCREASING" | "DECREASING" | "STABLE" | "INSUFFICIENT_DATA";
  change?: number;
  unit?: "PERCENT" | "PERCENTAGE_POINTS";
  earlierFrom?: string;
  earlierTo?: string;
  recentFrom?: string;
  recentTo?: string;
  earlierValue?: number;
  recentValue?: number;
  basis?: string;
  note?: string;
}

export interface MarketSkillMove {
  skillId: number;
  skill: string;
  category?: string;
  postings: number;
  earlierSharePercentage: number;
  recentSharePercentage: number;
  changeInPercentagePoints: number;
  direction: string;
}

/** Historical trends for a role or the whole market, and a separately labelled estimate. */
export interface MarketTrends {
  category?: string;
  period: {
    requestedMonths: number;
    fromMonth?: string;
    toMonth?: string;
    coveredMonths: number;
    monthsWithoutData: string[];
    latestPostedDate?: string;
    source: string;
  };
  volume: { month: string; postings?: number; allPostings?: number; sharePercentage?: number }[];
  volumeTrend: MarketTrend;
  shareTrend?: MarketTrend;
  skills: {
    source?: string;
    earlierFrom?: string;
    earlierTo?: string;
    recentFrom?: string;
    recentTo?: string;
    growing: MarketSkillMove[];
    declining: MarketSkillMove[];
    note?: string;
  };
  salary?: {
    currency: string;
    series: { month: string; currency: string; postings: number; averageMin?: number; averageMax?: number; reliable: boolean }[];
    trend: MarketTrend;
    note?: string;
  };
  locations: { locationId: number; location: string; postings: number; trend: MarketTrend }[];
  workModes: { month: string; total: number; remote: number; hybrid: number; onSite: number; notStated: number }[];
  workModeTrends: { mode: string; trend: MarketTrend }[];
  forecast: {
    status: "ESTIMATE" | "INSUFFICIENT_DATA";
    label: string;
    method: string;
    basedOnMonths?: number;
    fromMonth?: string;
    toMonth?: string;
    slopePerMonth?: number;
    rSquared?: number;
    estimates: { month: string; estimatedPostings: number }[];
    note?: string;
  };
  notes: string[];
}

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

/** V7.7: the signed-in user's career dashboard, one section per existing feature. */
export interface PersonalDashboard {
  resume: {
    available: boolean;
    current?: { id: string; title: string; versionLabel?: string; isDefault: boolean; skillCount: number; uploadedAt: string };
    resumeCount: number;
    matchSummary?: { jobsCompared: number; topMatchPercentage?: number; averageMatchPercentage?: number };
    missingSkillsForGoal: string[];
    note?: string;
  };
  skills: {
    available: boolean;
    currentSkills: Skill[];
    inProgress: string[];
    completed: string[];
    notStarted: number;
    roadmapPercentComplete?: number;
    note?: string;
  };
  recommendations: {
    available: boolean;
    count: number;
    averageMatchPercentage?: number;
    topJobs: { jobId: number; title: string; company: string; category?: string; matchPercentage: number }[];
    note?: string;
  };
  applications: {
    available: boolean;
    total: number;
    saved: number;
    applied: number;
    interview: number;
    offer: number;
    rejected: number;
    withdrawn: number;
    funnel: { stage: ApplicationStatus; jobs: number }[];
    recent: { savedJobId: string; jobId: number; title: string; company: string; status: ApplicationStatus; appliedAt?: string; updatedAt: string }[];
    note?: string;
  };
  careerGoal: {
    available: boolean;
    goalId?: string;
    targetRole?: string;
    targetCategory?: string;
    activeGoals: number;
    progress?: Roadmap['progress'];
    topMissingSkills: { priority: number; skill: string; reason: string; status: SkillProgressStatus }[];
    basedOnResume?: string;
    note?: string;
  };
  market: {
    available: boolean;
    category?: string;
    scope: MarketScope;
    topSkills: MarketSkills['topSkills'];
    skillTrend?: SkillTrends;
    topLocations: MarketLocations['topLocations'];
    locationNotStated: number;
    workModes: MarketRemote['distribution'];
    salaries: SalaryFigure[];
    topCompanies: MarketCompanies['topCompanies'];
    notes: string[];
  };
}

/** V9.5: the learning plan. */
export type LearningStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED';
export type LearningPriority = 'HIGH' | 'MEDIUM' | 'LOW';
export type LearningResourceType = 'COURSE' | 'VIDEO' | 'ARTICLE' | 'DOCUMENTATION' | 'PROJECT' | 'OTHER';

export interface LearningResource {
  id: string;
  title: string;
  url: string;
  type: LearningResourceType;
  notes?: string;
  createdAt: string;
}

export interface LearningResourceInput {
  title: string;
  url: string;
  type: LearningResourceType;
  notes?: string;
}

export interface LearningItem {
  id: string;
  goalId?: string;
  skillId?: number;
  skill: string;
  topic: string;
  priority: LearningPriority;
  status: LearningStatus;
  progress: number;
  targetDate?: string;
  notes?: string;
  createdAt: string;
  updatedAt: string;
  startedAt?: string;
  completedAt?: string;
  /** The skill's status on the career goal's roadmap, when the item serves one. */
  roadmapStatus?: LearningStatus;
  resources: LearningResource[];
}

export interface LearningItemInput {
  skillId?: number;
  skillName?: string;
  topic: string;
  priority?: LearningPriority;
  targetDate?: string;
  notes?: string;
}

export interface LearningItemUpdate {
  topic: string;
  priority: LearningPriority;
  progress: number;
  targetDate?: string;
  notes?: string;
}

export interface LearningPrioritySkill {
  rank: number;
  skillId: number;
  skill: string;
  reason: string;
  roadmapStatus?: LearningStatus;
  suggestedPriority: LearningPriority;
  itemId?: string;
}

export interface LearningPlan {
  goalId?: string;
  goalRole?: string;
  priorities: LearningPrioritySkill[];
  items: LearningItem[];
  progress: {
    items: number;
    notStarted: number;
    inProgress: number;
    completed: number;
    averageProgress?: number;
    completedSkills: string[];
  };
  impact: {
    goalRole?: string;
    roadmapSkills?: number;
    roadmapCompleted?: number;
    roadmapPercentComplete?: number;
    completedNotOnResume?: string[];
    savedJobDemand?: { skill: string; savedJobs: number }[];
    note?: string;
  };
  note?: string;
}

/** V9.7: the professional portfolio; section shapes are the resume builder's. */
export interface PortfolioContent {
  headline?: string;
  about?: string;
  skills: string[];
  experience: NonNullable<BuilderContent['experience']>;
  education: NonNullable<BuilderContent['education']>;
  projects: NonNullable<BuilderContent['projects']>;
  certifications: NonNullable<BuilderContent['certifications']>;
  achievements: string[];
  links: { label: string; url: string }[];
}

export interface PortfolioSections {
  about: boolean;
  skills: boolean;
  experience: boolean;
  education: boolean;
  projects: boolean;
  certifications: boolean;
  achievements: boolean;
  careerGoals: boolean;
  links: boolean;
}

export interface Portfolio {
  slug: string;
  displayName: string;
  visibility: 'PRIVATE' | 'PUBLIC';
  publicPath: string;
  content: PortfolioContent;
  sections: PortfolioSections;
  createdAt: string;
  updatedAt: string;
  publishedAt?: string;
}

export interface PortfolioInput {
  displayName: string;
  slug?: string;
  content: PortfolioContent;
  sections: PortfolioSections;
}

/** What /profile/{slug} shows; hidden sections are absent. */
export interface PublicProfile {
  displayName: string;
  headline?: string;
  about?: string;
  skills?: string[];
  experience?: PortfolioContent['experience'];
  education?: PortfolioContent['education'];
  projects?: PortfolioContent['projects'];
  certifications?: PortfolioContent['certifications'];
  achievements?: string[];
  careerGoals?: string[];
  links?: PortfolioContent['links'];
  updatedAt: string;
}

export interface PortfolioImport {
  displayName: string;
  content: PortfolioContent;
  learnedSkills: string[];
  source: 'BUILDER' | 'UPLOAD';
}
