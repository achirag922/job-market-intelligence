import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';

export type SuggestionKind = 'skill' | 'company' | 'location';

const SUGGESTION_LIMIT = 8;
const SUGGESTION_DELAY_MS = 250;
/** Every location the dataset holds is a few dozen rows; fetched once and filtered here. */
const LOCATION_FETCH_SIZE = 100;

/**
 * Names to offer while someone types into a filter.
 *
 * <p>Built on the endpoints that already exist rather than a new search-as-you-type API:
 * skills and companies have a name filter, so they are asked for matches once the typing
 * pauses. Locations have no name filter, but the whole set is small, so it is fetched
 * once and matched in the browser.
 *
 * <p>Suggestions are a convenience, so a failure is silent: the field still works as a
 * plain text filter, and an error message for a missing hint would be noise.
 */
export function useSuggestions(kind: SuggestionKind, text: string): string[] {
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const needle = text.trim();
  // Loaded on the first keystroke, not on mount: most searches never touch the location
  // box, and fetching its options for every page view would be a request for nothing.
  const locations = useLocationNames(kind === 'location' && needle.length > 0);

  useEffect(() => {
    // Nothing typed means nothing to suggest, which the return below derives directly —
    // no need to clear state here and trigger a second render to say so.
    if (kind === 'location' || needle.length === 0) {
      return;
    }

    let superseded = false;
    const timer = setTimeout(() => {
      const request = kind === 'skill'
        ? api.skills(0, SUGGESTION_LIMIT, needle).then((page) => page.content.map((row) => row.name))
        : api.companies(0, SUGGESTION_LIMIT, needle).then((page) => page.content.map((row) => row.name));
      request
        .then((names) => {
          if (!superseded) {
            setSuggestions(names);
          }
        })
        .catch(() => {
          if (!superseded) {
            setSuggestions([]);
          }
        });
    }, SUGGESTION_DELAY_MS);

    // A slower response for an older prefix must not overwrite the current one.
    return () => {
      superseded = true;
      clearTimeout(timer);
    };
  }, [kind, needle]);

  if (needle.length === 0) {
    return [];
  }
  if (kind === 'location') {
    const lower = needle.toLowerCase();
    return locations.filter((name) => name.toLowerCase().includes(lower)).slice(0, SUGGESTION_LIMIT);
  }
  return suggestions;
}

/**
 * Every place name the dataset uses, once.
 *
 * <p>Cities, states and countries each become an option on their own, because the
 * location filter matches any of the three: "Karnataka" and "India" are both useful
 * things to type.
 */
function useLocationNames(enabled: boolean): string[] {
  const [names, setNames] = useState<string[]>([]);
  // Latches: the list is fetched the first time it is wanted and kept. Typing, clearing
  // the box and typing again must not fetch it again.
  const requested = useRef(false);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (!enabled || requested.current) {
      return;
    }
    requested.current = true;
    api
      .locations(0, LOCATION_FETCH_SIZE)
      .then((page) => {
        // Only unmounting discards the result. Clearing the box before it arrives must
        // not, or the one request would be thrown away and never made again.
        if (!mounted.current) {
          return;
        }
        const unique = new Set<string>();
        page.content.forEach((location) => {
          [location.city, location.state, location.country].forEach((part) => {
            if (part) {
              unique.add(part);
            }
          });
        });
        setNames([...unique].sort((a, b) => a.localeCompare(b)));
      })
      .catch(() => {
        // Suggestions only; the field still filters without them. Allow a later retry.
        requested.current = false;
      });
  }, [enabled]);

  return names;
}
