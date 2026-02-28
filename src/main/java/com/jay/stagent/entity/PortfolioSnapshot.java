package com.jay.stagent.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "portfolio_snapshots")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate snapshotDate;
    private double totalValueInr;
    private double cashInr;
    private double deployedInr;
    private double deployedPct;
    private int openPositions;
    private double unrealisedPnlInr;
    private double dayPnlInr;
    private double peakValueInr;      // For drawdown calculation
    private double drawdownFromPeakPct;
}
