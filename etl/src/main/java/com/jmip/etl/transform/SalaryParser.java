package com.jmip.etl.transform;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a salary range and its currency out of free text.
 *
 * <p>Handles {@code "120000-150000 USD"}, {@code "$120,000 - $150,000"},
 * {@code "from 90000 EUR"}, {@code "£75k"} and {@code "₹18,00,000"}.
 *
 * <p>Phrases like "competitive" mean the employer disclosed nothing, which is normal and
 * yields an empty range rather than an error.
 */
@Component
public class SalaryParser {

    private static final Set<String> MEANS_UNDISCLOSED = Set.of(
            "competitive", "negotiable", "doe", "depends on experience", "market rate",
            "as per industry standards", "not disclosed", "not specified", "unspecified", "n/a", "na");

    private static final Map<String, String> SYMBOL_TO_CURRENCY = Map.of(
            "$", "USD", "£", "GBP", "€", "EUR", "₹", "INR", "¥", "JPY", "C$", "CAD", "A$", "AUD", "S$", "SGD");

    private static final Pattern ISO_CODE = Pattern.compile("\\b([A-Z]{3})\\b");
    private static final Pattern AMOUNT = Pattern.compile("(\\d[\\d,.\\s]*)\\s*([kKmM])?");
    private static final Pattern UPPER_BOUND_ONLY = Pattern.compile(
            "\\b(?:up\\s+to|max(?:imum)?|under|below)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Either bound may be absent. A currency is required whenever an amount is present,
     * because the database refuses a salary it cannot compare or aggregate.
     */
    public record SalaryRange(BigDecimal min, BigDecimal max, String currency) {

        public static final SalaryRange NONE = new SalaryRange(null, null, null);

        public boolean hasAmount() {
            return min != null || max != null;
        }
    }

    public SalaryRange parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SalaryRange.NONE;
        }
        String text = raw.trim();
        String lowered = text.toLowerCase(Locale.ROOT);
        if (MEANS_UNDISCLOSED.contains(lowered)) {
            return SalaryRange.NONE;
        }

        String currency = detectCurrency(text);
        List<BigDecimal> amounts = extractAmounts(text);

        if (amounts.isEmpty()) {
            // Text that looks like a salary field but holds no number at all: treat as
            // undisclosed rather than failing the record.
            return SalaryRange.NONE;
        }
        if (currency == null) {
            throw new ValueParseException("Salary has no recognisable currency: '" + raw + "'");
        }

        BigDecimal min;
        BigDecimal max;
        if (amounts.size() == 1) {
            boolean upperBoundOnly = UPPER_BOUND_ONLY.matcher(text).find();
            min = upperBoundOnly ? null : amounts.get(0);
            max = upperBoundOnly ? amounts.get(0) : null;
        } else {
            min = amounts.get(0);
            max = amounts.get(1);
        }

        if (min != null && min.signum() < 0 || max != null && max.signum() < 0) {
            throw new ValueParseException("Negative salary: '" + raw + "'");
        }
        if (min != null && max != null && max.compareTo(min) < 0) {
            throw new ValueParseException("Salary range is inverted: '" + raw + "'");
        }
        return new SalaryRange(min, max, currency);
    }

    private String detectCurrency(String text) {
        Matcher isoCode = ISO_CODE.matcher(text);
        if (isoCode.find()) {
            return isoCode.group(1);
        }
        // Two-character symbols first, so "C$" is not read as a bare "$".
        for (Map.Entry<String, String> entry : SYMBOL_TO_CURRENCY.entrySet()) {
            if (entry.getKey().length() > 1 && text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        for (Map.Entry<String, String> entry : SYMBOL_TO_CURRENCY.entrySet()) {
            if (entry.getKey().length() == 1 && text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private List<BigDecimal> extractAmounts(String text) {
        // Strip ISO codes first so that the digits inside something like "USD2024" are
        // not mistaken for money.
        String withoutCodes = ISO_CODE.matcher(text).replaceAll(" ");
        List<BigDecimal> amounts = new java.util.ArrayList<>();
        Matcher matcher = AMOUNT.matcher(withoutCodes);
        while (matcher.find() && amounts.size() < 2) {
            String digits = matcher.group(1).replaceAll("[,\\s]", "");
            // A trailing dot belongs to the sentence, not to the number.
            digits = digits.replaceAll("\\.$", "");
            if (digits.isEmpty()) {
                continue;
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(digits);
            } catch (NumberFormatException e) {
                throw new ValueParseException("Unreadable salary amount in '" + text + "'");
            }
            String magnitude = matcher.group(2);
            if (magnitude != null) {
                amount = amount.multiply(magnitude.equalsIgnoreCase("k")
                        ? BigDecimal.valueOf(1_000) : BigDecimal.valueOf(1_000_000));
            }
            amounts.add(amount);
        }
        return amounts;
    }
}
