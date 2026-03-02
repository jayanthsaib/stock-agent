package com.jay.stagent.layer1_data;

import com.jay.stagent.config.AgentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Determines whether the NSE market is open on a given date.
 * Checks weekends and the holiday list configured in config.yaml (market_holidays).
 *
 * Update market_holidays in config.yaml each January with the new NSE holiday calendar.
 * Official source: https://www.nseindia.com/resources/exchange-communication-holidays
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketCalendarService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final AgentConfig config;

    /** True if today is a market trading day (not weekend, not holiday). */
    public boolean isMarketOpen() {
        return isMarketOpen(LocalDate.now(IST));
    }

    /** True if the given date is a market trading day. */
    public boolean isMarketOpen(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;

        Set<String> holidays = config.marketHolidays().stream().collect(Collectors.toSet());
        return !holidays.contains(date.toString()); // "yyyy-MM-dd"
    }

    /**
     * Returns a human-readable market status string.
     * Used by GET /api/market/status.
     */
    public String getMarketStatus() {
        LocalDate today = LocalDate.now(IST);
        LocalTime now   = LocalTime.now(IST);

        if (!isMarketOpen(today)) {
            DayOfWeek dow = today.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
                return "CLOSED_WEEKEND";
            return "CLOSED_HOLIDAY";
        }
        if (now.isBefore(MARKET_OPEN))  return "PRE_MARKET";
        if (now.isAfter(MARKET_CLOSE))  return "AFTER_HOURS";
        return "OPEN";
    }

    /** Returns the holiday list loaded from config for the current year. */
    public java.util.List<String> getHolidays() {
        return config.marketHolidays();
    }
}
