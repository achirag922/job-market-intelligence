package com.jmip.mapper;

import com.jmip.dto.CompanyResponse;
import com.jmip.dto.ExperienceResponse;
import com.jmip.dto.JobDetailResponse;
import com.jmip.dto.JobSummaryResponse;
import com.jmip.dto.LocationResponse;
import com.jmip.dto.SalaryResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.entity.Company;
import com.jmip.entity.Job;
import com.jmip.entity.Location;
import com.jmip.entity.Skill;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Turns entities into the shapes the API exposes. Keeping this separate is what stops a
 * JPA entity, and with it the schema, leaking into the contract.
 */
@Component
public class JobMapper {

    /**
     * @param skills loaded for the whole page in one query, rather than read off the
     *               entity, which would trigger a lazy load per row
     */
    public JobSummaryResponse toSummary(Job job, List<SkillResponse> skills) {
        return new JobSummaryResponse(
                job.getId(),
                job.getTitle(),
                toCompany(job.getCompany()),
                toLocation(job.getLocation()),
                job.getEmploymentType(),
                ExperienceResponse.of(job.getExperienceMin(), job.getExperienceMax()),
                SalaryResponse.of(job.getSalaryMin(), job.getSalaryMax(), job.getCurrency()),
                job.getPostedDate(),
                skills == null ? List.of() : skills);
    }

    public JobDetailResponse toDetail(Job job) {
        return new JobDetailResponse(
                job.getId(),
                job.getTitle(),
                toCompany(job.getCompany()),
                toLocation(job.getLocation()),
                job.getDescription(),
                job.getEmploymentType(),
                ExperienceResponse.of(job.getExperienceMin(), job.getExperienceMax()),
                SalaryResponse.of(job.getSalaryMin(), job.getSalaryMax(), job.getCurrency()),
                job.getPostedDate(),
                job.getSource(),
                job.getSourceUrl(),
                toSkills(job.getSkills()),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }

    public CompanyResponse toCompany(Company company) {
        if (company == null) {
            return null;
        }
        return new CompanyResponse(company.getId(), company.getName(), company.getIndustry(), company.getWebsite());
    }

    public LocationResponse toLocation(Location location) {
        if (location == null) {
            return null;
        }
        return new LocationResponse(
                location.getId(),
                location.getCity(),
                location.getState(),
                location.getCountry(),
                location.displayName());
    }

    public SkillResponse toSkill(Skill skill) {
        return new SkillResponse(skill.getId(), skill.getName(), skill.getCategory());
    }

    private List<SkillResponse> toSkills(Iterable<Skill> skills) {
        if (skills == null) {
            return List.of();
        }
        List<SkillResponse> mapped = new java.util.ArrayList<>();
        skills.forEach(skill -> mapped.add(toSkill(skill)));
        mapped.sort(Comparator.comparing(SkillResponse::name));
        return mapped;
    }
}
