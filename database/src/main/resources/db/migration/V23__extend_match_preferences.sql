-- V9.2: personalization preferences, on the V8.3 match preferences row of the same account.
-- Lists are small (at most 20 entries each) and only ever read or written by the owner.

ALTER TABLE match_preferences
    ADD COLUMN preferred_categories TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN preferred_skills     TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN excluded_companies   TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN excluded_locations   TEXT[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT ck_match_preferences_list_sizes CHECK (
        cardinality(preferred_categories) <= 20 AND cardinality(preferred_skills) <= 20
        AND cardinality(excluded_companies) <= 20 AND cardinality(excluded_locations) <= 20);
