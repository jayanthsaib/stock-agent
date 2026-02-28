package com.jay.stagent.repository;

import com.jay.stagent.entity.TradeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TradeRecordRepository extends JpaRepository<TradeRecord, String> {

    List<TradeRecord> findByStatus(String status);

    List<TradeRecord> findBySymbol(String symbol);

    List<TradeRecord> findByGeneratedAtAfter(LocalDateTime since);

    @Query("SELECT t FROM TradeRecord t WHERE t.status = 'PENDING_APPROVAL' AND t.expiresAt < :now")
    List<TradeRecord> findExpiredPendingSignals(LocalDateTime now);

    @Query("SELECT t FROM TradeRecord t WHERE t.status = 'APPROVED' AND t.executedAt IS NULL")
    List<TradeRecord> findApprovedButUnexecuted();

    @Query("SELECT COUNT(t) FROM TradeRecord t WHERE t.signalType = 'BUY' AND t.generatedAt > :since")
    long countNewBuysSince(LocalDateTime since);

    Optional<TradeRecord> findByBrokerOrderId(String brokerOrderId);

    @Query("SELECT AVG(t.realisedPnlPct) FROM TradeRecord t WHERE t.closedAt IS NOT NULL AND t.closedAt > :since")
    Double avgRealisedPnlSince(LocalDateTime since);

    @Query("SELECT t FROM TradeRecord t WHERE t.closedAt IS NOT NULL ORDER BY t.closedAt DESC")
    List<TradeRecord> findAllClosed();
}
