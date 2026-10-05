-- V9.1: which connector each ETL run read its records through ('file', 'sample', ...).
-- Runs recorded before V9.1 could only read files; those with a file feed are marked as such.

ALTER TABLE etl_run_metrics ADD COLUMN connector VARCHAR(50);

UPDATE etl_run_metrics SET connector = 'file' WHERE feed_type IN ('FILE_JSON', 'FILE_CSV');
