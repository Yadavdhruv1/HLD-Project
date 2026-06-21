package com.typeahead.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuerySuggestion {
    private String query;
    private Long count;
    private Double recentCount;
    private Double score;

    public QuerySuggestion(String query, Long count) {
        this.query = query;
        this.count = count;
        this.recentCount = 0.0;
        this.score = (double) count;
    }
}
