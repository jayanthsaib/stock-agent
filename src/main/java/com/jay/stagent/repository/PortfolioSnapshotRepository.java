package com.jay.stagent.repository;

import com.jay.stagent.entity.PortfolioSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshot, Long> {

    Optional<PortfolioSnapshot> findTopByOrderBySnapshotDateDesc();

    Optional<PortfolioSnapshot> findBySnapshotDate(LocalDate date);

    @Query("SELECT MAX(s.totalValueInr) FROM PortfolioSnapshot s")
    Double findPeakPortfolioValue();
}
