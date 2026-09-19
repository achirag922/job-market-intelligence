package com.jmip.etl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * The skill vocabulary, loaded from configuration rather than compiled into the
 * processor. Adding a skill, an alias or a category is a configuration change, which is
 * the whole point: the vocabulary will change far more often than the extraction code.
 *
 * @param skills every skill the extractor knows about
 */
@ConfigurationProperties(prefix = "jmip.etl.skill-dictionary")
public record SkillDictionaryProperties(List<SkillDefinition> skills) {

    public SkillDictionaryProperties {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /**
     * @param name         canonical name, stored in {@code skills.name} and shown in analytics
     * @param category     coarse grouping, stored in {@code skills.category}
     * @param aliases      other spellings that mean the same skill, matched case-insensitively
     * @param supersededBy more specific skills that, when also matched, make this one
     *                     redundant. "Spring Boot" supersedes "Spring", so a posting
     *                     mentioning only Spring Boot does not inflate demand for Spring.
     */
    public record SkillDefinition(
            String name,
            String category,
            List<String> aliases,
            List<String> supersededBy) {

        public SkillDefinition {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            supersededBy = supersededBy == null ? List.of() : List.copyOf(supersededBy);
        }

        /** Canonical name plus every alias: all the spellings worth searching for. */
        public List<String> allTerms() {
            return java.util.stream.Stream.concat(java.util.stream.Stream.of(name), aliases.stream()).toList();
        }
    }
}
