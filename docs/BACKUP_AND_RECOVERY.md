# Backup and recovery

JMIP keeps its state in three places. All three are needed to recover a working system.

| What | Where (Docker Compose) | Why it matters |
| --- | --- | --- |
| PostgreSQL | volume `jmip_postgres-data` | accounts, jobs, resumes' metadata and (encrypted) extracted text, saved jobs, goals, alerts, ETL history |
| Resume files | volume `jmip_resume-data` (`JMIP_RESUME_DIR`) | the uploaded PDFs, encrypted when `JMIP_RESUME_ENCRYPTION_KEY` is set |
| Secrets | your secret store / `.env` (never the repository) | `JMIP_RESUME_ENCRYPTION_KEY`, `JMIP_DB_PASSWORD`, `JMIP_OTP_SECRET`, mail and AI keys |

**Back up the encryption key separately from the data, and never lose it.** Without the same
`JMIP_RESUME_ENCRYPTION_KEY`, restored resume files and extracted text cannot be read. Changing
`JMIP_OTP_SECRET` only invalidates verification codes that are still pending.

Automated, scheduled, off-site backups depend on where JMIP is deployed and are not part of this
repository. The commands below are the building blocks; run them from the directory with
`docker-compose.yml`.

## Back up

```bash
# 1. Database: a consistent logical dump, taken while the application runs.
docker compose exec -T postgres pg_dump -U "${JMIP_DB_USERNAME:-jmip}" -Fc "${JMIP_DB_NAME:-jmip}" > "jmip-db-$(date +%F).dump"

# 2. Resume files, straight after the dump so the two agree as closely as possible.
docker run --rm -v jmip_resume-data:/data:ro -v "$PWD":/backup alpine tar czf "/backup/jmip-resumes-$(date +%F).tgz" -C /data .
```

Store both files off the host, encrypted at rest, with a retention period that matches the
resume-retention policy (`JMIP_RESUME_RETENTION`): a backup keeps deleted resumes until the backup
itself expires.

## Restore

```bash
# 1. Start only the database, with the same credentials as before.
docker compose up -d postgres

# 2. Restore the dump into it (drops and recreates the objects it contains).
docker compose exec -T postgres pg_restore -U "${JMIP_DB_USERNAME:-jmip}" -d "${JMIP_DB_NAME:-jmip}" --clean --if-exists < jmip-db-YYYY-MM-DD.dump

# 3. Restore the resume files.
docker run --rm -v jmip_resume-data:/data -v "$PWD":/backup alpine sh -c "rm -rf /data/* && tar xzf /backup/jmip-resumes-YYYY-MM-DD.tgz -C /data"

# 4. Start everything with the SAME secrets as when the backup was taken.
docker compose up -d
```

On startup Flyway validates the restored schema against the migrations (a newer backup on an
older build fails loudly rather than silently), and the skill-demand history is rebuilt from the
restored postings.

## Checks after a restore

- `curl http://localhost:3000/actuator/health/readiness` returns `{"status":"UP"}`.
- Sign in with a known account; its resumes, saved jobs and goals are there.
- Open one resume in Resume Intelligence: if it fails to load, the encryption key does not match.

Test the restore regularly on a separate machine; an untested backup is not a backup.
