package com.redditclone.common.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Builds the "as you type" half of full-text search. websearch_to_tsquery only matches whole (stemmed) words,
// so "hel" finds nothing until the user finishes typing "hello". This produces a to_tsquery expression where every
// word must match and the LAST word is a prefix ("fruit & mang:*"). Tokens are reduced to letters and digits, so
// the result can never be a malformed tsquery regardless of what the user typed. Callers OR it with the websearch
// query so quoted phrases, "or" and "-exclusions" keep working.
public final class SearchTerms {

    private static final int MAX_TERMS = 8;

    private SearchTerms() {
    }

    // "" (never null) when the input has no searchable characters.
    public static String prefixTsQuery(String raw) {
        if (raw == null) {
            return "";
        }
        List<String> terms = new ArrayList<>();
        for (String t : raw.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!t.isEmpty() && terms.size() < MAX_TERMS) {
                terms.add(t);
            }
        }
        if (terms.isEmpty()) {
            return "";
        }
        int last = terms.size() - 1;
        terms.set(last, terms.get(last) + ":*");
        return String.join(" & ", terms);
    }
}
