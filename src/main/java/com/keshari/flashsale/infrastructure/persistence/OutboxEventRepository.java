package com.keshari.flashsale.infrastructure.persistence;

import com.keshari.flashsale.domain.OutboxEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	/**
	 * Next batch of unpublished events in insertion order. {@code FOR UPDATE SKIP
	 * LOCKED} lets several relay instances drain the table concurrently without
	 * publishing the same event twice.
	 */
	@Query(value = """
			SELECT * FROM outbox_event
			 WHERE published_at IS NULL
			 ORDER BY id
			 LIMIT :batchSize
			 FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<OutboxEvent> lockNextUnpublished(@Param("batchSize") int batchSize);

	long countByPublishedAtIsNull();
}
