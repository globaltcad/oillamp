package dev.gui.model;

import java.time.Duration;
import java.time.LocalDate;

/// Days and spans of time in the words the schedule page uses, the [Lang.Dates] of a language.
public final class DateWordingUtil {

    private DateWordingUtil() {}

    /// Whether `day` is today, tomorrow or yesterday, which are said as words rather than dates.
    public static boolean near(LocalDate day, LocalDate today) {
        return !day.isBefore(today.minusDays(1)) && !day.isAfter(today.plusDays(1));
    }

    /// "Today", "Tomorrow", "Yesterday", or the day written out, such as "Thursday 2 October".
    public static String day(LocalDate day, LocalDate today, Lang.Dates words) {
        if (day.equals(today)) return words.TODAY();
        if (day.equals(today.plusDays(1))) return words.TOMORROW();
        if (day.equals(today.minusDays(1))) return words.YESTERDAY();
        return words.DAY(day);
    }

    /// The same, as it stands after a word such as "until": "tomorrow", "Thursday 2 October".
    public static String dayAfterAWord(LocalDate day, LocalDate today, Lang.Dates words) {
        if (day.equals(today)) return words.TODAY_IN_A_SENTENCE();
        if (day.equals(today.plusDays(1))) return words.TOMORROW_IN_A_SENTENCE();
        if (day.equals(today.minusDays(1))) return words.YESTERDAY_IN_A_SENTENCE();
        return words.DAY(day);
    }

    /// The same, for the middle of a sentence: "tomorrow", "on Thursday 2 October".
    public static String dayInSentence(LocalDate day, LocalDate today, Lang.Dates words) {
        return near(day, today) ? dayAfterAWord(day, today, words) : words.ON_DAY(words.DAY(day));
    }

    /// "Thu 2 Oct".
    public static String shortDay(LocalDate day, Lang.Dates words) {
        return words.SHORT_DAY(day);
    }

    /// How far off something is, roughly: "in 5 minutes", "in 3 hours", "in 2 days". Under a
    /// minute is "now".
    public static String fromNow(Duration ahead, Lang.Dates words) {
        long minutes = ahead.toMinutes();
        if (minutes < 1) return words.NOW();
        if (minutes < 60) return words.IN_MINUTES((int) minutes);
        long hours = Math.round(minutes / 60.0);
        if (hours < 24) return words.IN_HOURS((int) hours);
        long days = Math.round(hours / 24.0);
        return words.IN_DAYS((int) days);
    }

    /// How long ago something was: "just now", "5 minutes ago", "3 hours ago", "2 days ago".
    public static String ago(Duration since, Lang.Dates words) {
        long minutes = since.toMinutes();
        if (minutes < 1) return words.JUST_NOW();
        if (minutes < 60) return words.MINUTES_AGO((int) minutes);
        long hours = Math.round(minutes / 60.0);
        if (hours < 24) return words.HOURS_AGO((int) hours);
        long days = Math.round(hours / 24.0);
        return words.DAYS_AGO((int) days);
    }
}
