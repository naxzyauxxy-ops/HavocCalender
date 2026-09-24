package com.havoc.havoccalendar.util;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.logging.Logger;

/**
 * All date logic lives here: the configurable time zone, December detection and the
 * administrative "simulate day X" override used by {@code /havoccalendar setday}.
 * <p>
 * Thread-safe: fields are volatile and the class holds no mutable collections.
 */
public final class DateUtils {

    public static final int FIRST_DAY = 1;
    public static final int LAST_ADVENT_DAY = 25;
    public static final int LAST_DECEMBER_DAY = 31;

    private final Logger logger;

    private volatile ZoneId zone = ZoneId.systemDefault();
    private volatile DateTimeFormatter unlockFormatter =
            DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH);
    /** 0 = no override; 1..31 = pretend it is December {@code overrideDay}. */
    private volatile int overrideDay = 0;

    public DateUtils(Logger logger) {
        this.logger = logger;
    }

    /** (Re)loads time zone, date format and the optional start-up override from config.yml. */
    public void load(FileConfiguration config) {
        String zoneId = config.getString("date.timezone", "server");
        if (zoneId == null || zoneId.isBlank() || zoneId.equalsIgnoreCase("server")) {
            zone = ZoneId.systemDefault();
        } else {
            try {
                zone = ZoneId.of(zoneId);
            } catch (DateTimeException ex) {
                logger.warning("Invalid time zone '" + zoneId + "' in config.yml, using the server default.");
                zone = ZoneId.systemDefault();
            }
        }

        String pattern = config.getString("date.unlock-date-format", "MMMM d");
        String localeTag = config.getString("date.locale", "en");
        try {
            unlockFormatter = DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag(localeTag));
        } catch (IllegalArgumentException ex) {
            logger.warning("Invalid date format '" + pattern + "', using 'MMMM d'.");
            unlockFormatter = DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH);
        }

        int configOverride = config.getInt("testing.override-day", 0);
        if (configOverride != 0) {
            if (isValidDecemberDay(configOverride)) {
                overrideDay = configOverride;
                logger.warning("testing.override-day is active: simulating December " + configOverride + ".");
            } else {
                logger.warning("testing.override-day must be between 1 and 31 (or 0 to disable).");
            }
        }
    }

    // ---------------------------------------------------------------- core queries

    public ZoneId zone() {
        return zone;
    }

    /** The real current date in the configured zone (ignores the override). */
    public LocalDate realToday() {
        return LocalDate.now(zone);
    }

    /** True if it is December, or an admin override is active. */
    public boolean isDecember() {
        return overrideDay > 0 || realToday().getMonth() == Month.DECEMBER;
    }

    /**
     * The current December day-of-month (1..31) taking the override into account,
     * or {@code 0} if it is not December.
     */
    public int currentDecemberDay() {
        int override = overrideDay;
        if (override > 0) {
            return override;
        }
        LocalDate today = realToday();
        return today.getMonth() == Month.DECEMBER ? today.getDayOfMonth() : 0;
    }

    /** The year claims are stored under, so the calendar resets automatically every December. */
    public int eventYear() {
        return realToday().getYear();
    }

    /** Days until the given advent day unlocks (0 = today / already unlocked). */
    public long daysUntil(int day) {
        int current = currentDecemberDay();
        if (current > 0) {
            return Math.max(0, day - current);
        }
        LocalDate today = realToday();
        LocalDate target = LocalDate.of(today.getYear(), Month.DECEMBER, day);
        if (target.isBefore(today)) {
            target = target.plusYears(1);
        }
        return ChronoUnit.DAYS.between(today, target);
    }

    /** Days until Christmas Day (Dec 25), 0 on/after Christmas in December. */
    public long daysUntilChristmas() {
        return daysUntil(LAST_ADVENT_DAY);
    }

    /** Human readable unlock date, e.g. "December 5". */
    public String formatUnlockDate(int day) {
        return unlockFormatter.format(LocalDate.of(eventYear(), Month.DECEMBER, day));
    }

    // ---------------------------------------------------------------- admin override

    public boolean isOverrideActive() {
        return overrideDay > 0;
    }

    public int overrideDay() {
        return overrideDay;
    }

    public void setOverrideDay(int day) {
        if (!isValidDecemberDay(day)) {
            throw new IllegalArgumentException("Override day must be 1-31, got " + day);
        }
        this.overrideDay = day;
    }

    public void clearOverride() {
        this.overrideDay = 0;
    }

    // ---------------------------------------------------------------- validation helpers

    public static boolean isValidAdventDay(int day) {
        return day >= FIRST_DAY && day <= LAST_ADVENT_DAY;
    }

    public static boolean isValidDecemberDay(int day) {
        return day >= FIRST_DAY && day <= LAST_DECEMBER_DAY;
    }

    public static OptionalInt parseInt(String input) {
        if (input == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(input.trim()));
        } catch (NumberFormatException ex) {
            return OptionalInt.empty();
        }
    }
}
