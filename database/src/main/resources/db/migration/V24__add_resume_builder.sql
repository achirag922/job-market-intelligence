-- V9.4: resumes written in JMIP's builder, alongside uploaded ones.
--
-- A built resume is an ordinary resumes row (versions, default, deletion, analysis and matching all
-- apply) whose sections are kept in builder_content (JSON, encrypted at rest like extracted_text
-- when a key is configured). Its extracted_text is generated from those sections on every save.

ALTER TABLE resumes
    ADD COLUMN source          VARCHAR(10) NOT NULL DEFAULT 'UPLOAD',
    ADD COLUMN builder_content TEXT,
    ADD CONSTRAINT ck_resumes_source CHECK (source IN ('UPLOAD', 'BUILDER')),
    ADD CONSTRAINT ck_resumes_builder_content CHECK ((source = 'BUILDER') = (builder_content IS NOT NULL));

COMMENT ON COLUMN resumes.builder_content IS 'V9.4: the builder''s sections as JSON; private to the owner, never logged.';
