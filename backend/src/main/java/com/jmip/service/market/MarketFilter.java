package com.jmip.service.market;

import com.jmip.service.analytics.ExperienceBucket;

import java.time.LocalDate;

/**
 * V7.5: which postings a market view covers. Each field narrows it the way the job search
 * does: exact category, location text in city/state/country, experience band by the posting's
 * minimum, and posting months from {@code from} on. Null means "no restriction".
 */
public record MarketFilter(String category, String location, ExperienceBucket experience, LocalDate from) {

    public boolean narrowsPostings() {
        return category != null || location != null || experience != null || from != null;
    }
}
