package com.typeahead.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "search_query",
    indexes = {
        @Index(name = "idx_query", columnList = "query"),
        @Index(name = "idx_total_count", columnList = "total_count")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "query", nullable = false, unique = true)
    private String query;

    @Column(name = "total_count", nullable = false)
    private Long totalCount;

    @Column(name = "recent_count", nullable = false)
    private Double recentCount;

    @Column(name = "last_updated_at", nullable = false)
    private LocalDateTime lastUpdatedAt;
}
