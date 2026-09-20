package com.jmip.repository.projection;

import com.jmip.entity.Skill;

/**
 * One job-to-skill pairing, used to load the skills for a whole page of jobs in a single
 * query instead of one query per job.
 */
public record JobSkillRow(Long jobId, Skill skill) {
}
