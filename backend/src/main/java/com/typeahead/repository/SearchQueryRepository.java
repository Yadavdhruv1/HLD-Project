package com.typeahead.repository;

import com.typeahead.model.SearchQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SearchQueryRepository extends JpaRepository<SearchQuery, Long> {

    Optional<SearchQuery> findByQuery(String query);

    @Query("SELECT s FROM SearchQuery s WHERE s.query IN :queries")
    List<SearchQuery> findAllByQueries(@Param("queries") List<String> queries);

    @Query(value = "SELECT * FROM search_query WHERE LOWER(query) LIKE LOWER(CONCAT(:prefix, '%')) ORDER BY total_count DESC LIMIT 10", nativeQuery = true)
    List<SearchQuery> findTop10SuggestionsByPrefix(@Param("prefix") String prefix);
}
