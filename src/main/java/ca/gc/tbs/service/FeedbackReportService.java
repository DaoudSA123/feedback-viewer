package ca.gc.tbs.service;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class FeedbackReportService {

  public enum Scope {
    PAGE,
    GROUP,
    DEPARTMENT,
    GC
  }

  public static final double BENCHMARK_YES_RATE = 0.70;

  static final double BENCHMARK_MARGIN = 0.05;

  static final int MIN_RESPONSES = 30;

  static final int MAX_GROUP_URLS = 50;
  static final int MAX_RANGE_YEARS = 5;

  static final int COMMENTS_SHOWN = 100;

  static final int MOCK_COMMENT_ROWS = 1000;

  public static final List<String> DEPARTMENTS =
      List.of(
          "AAFC",
          "CBSA",
          "CCG",
          "CER",
          "CFIA",
          "CIRNAC",
          "CRA",
          "CSC",
          "CSE",
          "DFO",
          "DND",
          "ECCC",
          "ESDC",
          "FCAC",
          "FIN",
          "GAC",
          "HC",
          "HICC",
          "IRCC",
          "ISC",
          "ISED",
          "LAC",
          "NRC",
          "NRCAN",
          "NSERC",
          "OMBDNDCAF",
          "PS",
          "PSPC",
          "PHAC",
          "SC",
          "SSHRC",
          "SST",
          "TBS");

  private static final List<String> MOCK_COMMENTS =
      List.of(
          "I couldn't find the application form.",
          "The page doesn't say how long processing takes.",
          "The link to sign in is broken.",
          "Too much text, I just need the eligibility criteria.",
          "Je ne trouve pas les dates limites.",
          "Le formulaire ne fonctionne pas sur mon téléphone.",
          "The phone number listed is out of date.",
          "I don't understand which option applies to me.",
          "Il manque des renseignements sur les frais.",
          "The search did not return the page I needed.");

  public record ReportRequest(
      Scope scope, List<String> targets, LocalDate startDate, LocalDate endDate) {}

  /** Previous completed GC fiscal quarter. The fiscal year starts on April 1. */
  public record FiscalQuarter(LocalDate start, LocalDate end) {}

  public record DailyStats(String date, long pageViews, long yes, long no) {}

  public record ReportComment(String date, String url, String language, String comment) {}

  public record Report(
      Scope scope,
      List<String> targets,
      String startDate,
      String endDate,
      long pageViews,
      long yes,
      long no,
      Double yesRate,
      Double noRate,
      Double responseRate,
      double benchmarkYesRate,
      String assessment,
      long commentCount,
      List<DailyStats> daily,
      List<ReportComment> comments) {}

  public static FiscalQuarter lastFiscalQuarter(LocalDate today) {
    int fiscalYear =
        today.getMonthValue() >= Month.APRIL.getValue() ? today.getYear() : today.getYear() - 1;
    LocalDate fiscalStart = LocalDate.of(fiscalYear, Month.APRIL, 1);
    int quarterIndex = (int) ChronoUnit.MONTHS.between(fiscalStart, today.withDayOfMonth(1)) / 3;
    LocalDate start = fiscalStart.plusMonths((quarterIndex - 1L) * 3);
    return new FiscalQuarter(start, start.plusMonths(3).minusDays(1));
  }

  public ReportRequest parseRequest(String scope, String value, String startDate, String endDate) {
    Scope parsedScope = parseScope(scope);
    LocalDate start = parseDate(startDate, "startDate");
    LocalDate end = parseDate(endDate, "endDate");
    if (start.isAfter(end)) {
      throw new IllegalArgumentException("startDate is after endDate");
    }
    if (start.isBefore(lastFiscalQuarter(LocalDate.now()).start())) {
      throw new IllegalArgumentException("startDate is before the last quarter");
    }
    if (start.plusYears(MAX_RANGE_YEARS).isBefore(end)) {
      throw new IllegalArgumentException("Date range is longer than " + MAX_RANGE_YEARS + " years");
    }
    return new ReportRequest(parsedScope, parseTargets(parsedScope, value), start, end);
  }

  public Report generate(ReportRequest request) {
    List<DailyStats> daily = dailyStats(request);
    long pageViews = daily.stream().mapToLong(DailyStats::pageViews).sum();
    long yes = daily.stream().mapToLong(DailyStats::yes).sum();
    long no = daily.stream().mapToLong(DailyStats::no).sum();
    Double yesRate = yes + no == 0 ? null : (double) yes / (yes + no);
    Double noRate = yes + no == 0 ? null : (double) no / (yes + no);
    Double responseRate = pageViews == 0 ? null : (double) (yes + no) / pageViews;

    return new Report(
        request.scope(),
        request.targets(),
        request.startDate().toString(),
        request.endDate().toString(),
        pageViews,
        yes,
        no,
        yesRate,
        noRate,
        responseRate,
        BENCHMARK_YES_RATE,
        assess(yes + no, yesRate),
        commentCount(daily),
        daily,
        comments(request).limit(COMMENTS_SHOWN).toList());
  }

  public Stream<ReportComment> comments(ReportRequest request) {
    List<String> urls =
        request.scope() == Scope.PAGE || request.scope() == Scope.GROUP
            ? request.targets()
            : List.of("https://www.canada.ca/en/services.html");
    long total = commentCount(dailyStats(request));
    return IntStream.range(0, (int) Math.min(total, MOCK_COMMENT_ROWS))
        .mapToObj(
            i -> {
              var random = new Random(seed(request.scope(), request.targets(), i));
              String comment = MOCK_COMMENTS.get(random.nextInt(MOCK_COMMENTS.size()));
              String language = comment.matches(".*[éèàçô].*|^(Je|Le|Il) .*") ? "fr" : "en";
              LocalDate date =
                  request
                      .endDate()
                      .minusDays(i % (dayCount(request.startDate(), request.endDate())));
              return new ReportComment(
                  date.toString(), urls.get(i % urls.size()), language, comment);
            });
  }

  static String assess(long responses, Double yesRate) {
    if (yesRate == null || responses < MIN_RESPONSES) return "INSUFFICIENT_DATA";
    if (yesRate >= BENCHMARK_YES_RATE + BENCHMARK_MARGIN) return "ABOVE";
    if (yesRate <= BENCHMARK_YES_RATE - BENCHMARK_MARGIN) return "BELOW";
    return "NEAR";
  }

  // Seeded from the target and the date, so the same request matches and a group sums its pages
  private List<DailyStats> dailyStats(ReportRequest request) {
    var days = new ArrayList<DailyStats>();
    for (LocalDate d = request.startDate(); !d.isAfter(request.endDate()); d = d.plusDays(1)) {
      if (request.scope() == Scope.PAGE || request.scope() == Scope.GROUP) {
        long views = 0, yes = 0, no = 0;
        for (String url : request.targets()) {
          DailyStats page = mockDay(Scope.PAGE, url, d, 200, 1500);
          views += page.pageViews();
          yes += page.yes();
          no += page.no();
        }
        days.add(new DailyStats(d.toString(), views, yes, no));
      } else if (request.scope() == Scope.DEPARTMENT) {
        days.add(mockDay(Scope.DEPARTMENT, request.targets().getFirst(), d, 20_000, 80_000));
      } else {
        days.add(mockDay(Scope.GC, "", d, 600_000, 1_200_000));
      }
    }
    return days;
  }

  // The target fixes its typical yes rate; the date adds day-to-day noise
  private static DailyStats mockDay(
      Scope scope, String target, LocalDate date, int minViews, int maxViews) {
    double typicalYesRate = 0.45 + new Random(seed(scope, target)).nextDouble() * 0.45;
    var random = new Random(seed(scope, target, date));
    long views = minViews + random.nextInt(maxViews - minViews);
    long responses = Math.round(views * (0.01 + random.nextDouble() * 0.03));
    double yesRate = Math.clamp(typicalYesRate + (random.nextDouble() - 0.5) * 0.1, 0, 1);
    long yes = Math.round(responses * yesRate);
    return new DailyStats(date.toString(), views, yes, responses - yes);
  }

  // About 40% of people who click "no" leave a comment
  private static long commentCount(List<DailyStats> daily) {
    return Math.round(daily.stream().mapToLong(DailyStats::no).sum() * 0.4);
  }

  private static long seed(Object... parts) {
    return Objects.hash(parts);
  }

  private static long dayCount(LocalDate start, LocalDate end) {
    return ChronoUnit.DAYS.between(start, end) + 1;
  }

  private static Scope parseScope(String scope) {
    if (scope == null) throw new IllegalArgumentException("scope is required");
    try {
      return Scope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown scope: " + scope);
    }
  }

  private static LocalDate parseDate(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(name + " must be YYYY-MM-DD");
    }
  }

  private static List<String> parseTargets(Scope scope, String value) {
    if (scope == Scope.GC) return List.of();
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) throw new IllegalArgumentException("A value is required for " + scope);

    if (scope == Scope.DEPARTMENT) {
      String code = trimmed.toUpperCase(Locale.ROOT);
      if (!DEPARTMENTS.contains(code)) throw new IllegalArgumentException("Unknown department");
      return List.of(code);
    }

    List<String> urls =
        scope == Scope.PAGE
            ? List.of(trimmed)
            : new ArrayList<>(
                new LinkedHashSet<>(
                    Arrays.stream(trimmed.split("[\\s,]+")).filter(s -> !s.isEmpty()).toList()));
    if (urls.size() > MAX_GROUP_URLS) {
      throw new IllegalArgumentException("A group can have at most " + MAX_GROUP_URLS + " URLs");
    }
    for (String url : urls) {
      if (!url.matches("(?i)https?://\\S+")) {
        throw new IllegalArgumentException("Not a web address: " + url);
      }
    }
    return List.copyOf(urls);
  }
}
