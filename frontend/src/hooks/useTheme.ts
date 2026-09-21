import { useCallback, useEffect, useState } from 'react';

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'jmip-theme';

/**
 * Light or dark, remembered between visits.
 *
 * <p>The stylesheet already defines both themes as values of the same variables, so this
 * only has to decide which one applies: it stamps `data-theme` on the document root and
 * lets CSS do the rest. Nothing re-renders for a colour change.
 *
 * <p>Until someone chooses, there is no attribute at all and the operating system's
 * preference wins through the media query — which is the right default, and means the
 * first paint is never the wrong theme.
 */
export function useTheme(): { theme: Theme; toggle: () => void } {
  const [theme, setTheme] = useState<Theme>(() => readStored() ?? systemTheme());

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      // Storage can be unavailable in private browsing. The theme still applies for
      // this visit; only remembering it fails, which is not worth an error.
    }
  }, [theme]);

  const toggle = useCallback(() => {
    setTheme((current) => (current === 'dark' ? 'light' : 'dark'));
  }, []);

  return { theme, toggle };
}

function readStored(): Theme | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === 'dark' || stored === 'light' ? stored : null;
  } catch {
    return null;
  }
}

function systemTheme(): Theme {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches
    ? 'dark'
    : 'light';
}
