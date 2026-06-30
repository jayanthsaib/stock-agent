package com.jay.stagent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MFSchemeData {

    private String schemeCode;
    private String schemeName;
    private String category;
    private String fundHouse;
    private boolean isDirect;

    private double currentNav;
    private double nav52wHigh;
    private double nav52wLow;

    private double cagr3y;
    private double sharpeRatio;
    private double return1y;
    private double return6m;
    private double return3m;
    private int consistencyCount;  // out of 9 rolling 12-month windows with positive return

    private List<NavPoint> navHistory;  // newest-first

    @Data
    @Builder
    public static class NavPoint {
        private String date;
        private double nav;
    }
}
