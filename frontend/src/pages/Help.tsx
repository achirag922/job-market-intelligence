import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Card, EmptyState, PageHeader } from '../components/ui';

interface Faq {
  q: string;
  a: string;
  link?: { to: string; label: string };
}

const SECTIONS: { id: string; title: string; faqs: Faq[] }[] = [
  { id: 'getting-started', title: 'Getting started', faqs: [
    { q: 'What should I do first?', a: 'Finish the short setup: your target role, experience and skills, a resume, job preferences and a career goal. Every step can be skipped and finished later, and the dashboard shows your next steps.', link: { to: '/onboarding', label: 'Open Profile & Preferences' } },
    { q: 'Where do I find my next recommended action?', a: 'On the Dashboard under "Your next steps". They come from what is still incomplete in your career readiness score.', link: { to: '/', label: 'Go to the dashboard' } },
  ] },
  { id: 'jobs', title: 'Jobs & matching', faqs: [
    { q: 'How is the match percentage worked out?', a: 'The skill match is the share of a job’s listed skills that your current resume shows. Recommendations also weigh experience, location, work mode, salary, your career goal and your preferences; each job lists the reasons.', link: { to: '/for-you', label: 'See recommendations' } },
    { q: 'What does "Not interested" do?', a: 'It hides the job from your search results and recommendations. You can undo it under Hidden in the Job Workspace.', link: { to: '/workspace', label: 'Open the Job Workspace' } },
    { q: 'How do follow-up reminders work?', a: 'Set a follow-up date on a saved job. When it is due you get a notification, and an email where email sending is set up; each date is reminded once.', link: { to: '/saved-jobs', label: 'Open Applications' } },
  ] },
  { id: 'resume', title: 'Resume', faqs: [
    { q: 'Why are some of my skills missing?', a: 'JMIP recognises skills from its skills dictionary. If a skill is written differently or not in the dictionary, it is not counted. Writing skills plainly in a Skills section helps.', link: { to: '/resume', label: 'Open Resume Intelligence' } },
    { q: 'Upload or build?', a: 'Both work the same for matching. The Resume Builder writes a clean, ATS-friendly resume you can export as PDF.', link: { to: '/resume-builder', label: 'Open the Resume Builder' } },
  ] },
  { id: 'growth', title: 'Career growth', faqs: [
    { q: 'What is the career readiness score?', a: 'A score out of 100 made of seven parts: profile, resume, skill gap, learning, interviews, job search and portfolio. Each part is capped, so repeating an action beyond what helps earns nothing.', link: { to: '/progress', label: 'See your progress' } },
    { q: 'Where does the skill gap come from?', a: 'From real job postings in your career goal’s category: the skills they ask for most that your resume does not show yet.', link: { to: '/career-goals', label: 'Open Career Goals' } },
    { q: 'How are practice interviews scored?', a: 'Each answer is scored 1 to 5 on relevance, completeness, clarity, communication and, for technical questions, correctness. The feedback never invents experience for you.', link: { to: '/interview-prep', label: 'Practise an interview' } },
  ] },
  { id: 'privacy', title: 'Privacy & account', faqs: [
    { q: 'Who can see my data?', a: 'Only you. Your resume, applications, notes, interviews and learning plan are private. Only a portfolio you publish is public, and only its visible sections.', link: { to: '/settings', label: 'Open Settings' } },
    { q: 'How do I delete my account?', a: 'In Settings, under Account. You confirm with your password; your resumes and all your data are deleted.', link: { to: '/settings#account', label: 'Open account settings' } },
  ] },
  { id: 'troubleshooting', title: 'Troubleshooting', faqs: [
    { q: 'A page says "Could not load this".', a: 'Use "Try again". If it keeps failing, check your connection and reload the page; your saved data is not affected.' },
    { q: 'I was sent back to the login page.', a: 'Your session ended, for example after a long time away. Log in again to continue; your saved data is kept.' },
    { q: 'A job shows no match percentage.', a: 'Matches need a processed resume. Upload or build one, then the percentages appear.', link: { to: '/resume', label: 'Add a resume' } },
  ] },
];

/** V9.18: answers to common questions, searchable, linking to where each thing is done. */
export function HelpPage() {
  const [query, setQuery] = useState('');
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return SECTIONS.map((section) => ({ ...section, faqs: section.faqs.filter((faq) =>
      !q || faq.q.toLowerCase().includes(q) || faq.a.toLowerCase().includes(q)) })).filter((section) => section.faqs.length > 0);
  }, [query]);

  return (
    <>
      <PageHeader title="Help & FAQ" description="How JMIP works, what its numbers mean and what to do when something goes wrong." />
      <label className="field" style={{ maxWidth: 420, marginBottom: 16 }}>
        Search help
        <input type="search" value={query} placeholder="e.g. match, resume, delete" onChange={(e) => setQuery(e.target.value)} />
      </label>
      {shown.length === 0 ? (
        <Card><EmptyState title="No answers found" message="Try another word, or browse the sections after clearing the search." /></Card>
      ) : shown.map((section) => (
        <Card key={section.id} title={section.title}>
          <div id={section.id} className="faq-list">
            {section.faqs.map((faq) => (
              <details key={faq.q} className="faq" open={query.trim() !== ''}>
                <summary>{faq.q}</summary>
                <p>{faq.a}</p>
                {faq.link && <Link to={faq.link.to}>{faq.link.label}</Link>}
              </details>
            ))}
          </div>
        </Card>
      ))}
    </>
  );
}
