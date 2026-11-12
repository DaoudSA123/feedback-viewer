package ca.gc.tbs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.gc.tbs.service.FeedbackReportService.Report;
import ca.gc.tbs.service.FeedbackReportService.ReportComment;
import ca.gc.tbs.service.FeedbackReportService.Scope;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedbackReportServiceTest {

  private static final String PAGE_A = "https://www.canada.ca/en/a.html";
  private static final String PAGE_B = "https://www.canada.ca/en/b.html";

  private final FeedbackReportService service = new FeedbackReportService();

  private Report report(String scope, String value) {
    return service.generate(service.parseRequest(scope, value, "2026-10-01", "2026-10-31"));
  }

  @Test
  void theSameRequestGivesTheSameReport() {
    assertThat(report("PAGE", PAGE_A)).isEqualTo(report("page", " " + PAGE_A + " "));
  }

  @Test
  void aGroupAddsUpToItsPages() {
    Report a = report("PAGE", PAGE_A);
    Report b = report("PAGE", PAGE_B);
    Report group = report("GROUP", PAGE_A + "\n" + PAGE_B + "\n" + PAGE_A);

    assertThat(group.targets()).containsExactly(PAGE_A, PAGE_B);
    assertThat(group.pageViews()).isEqualTo(a.pageViews() + b.pageViews());
    assertThat(group.yes()).isEqualTo(a.yes() + b.yes());
    assertThat(group.no()).isEqualTo(a.no() + b.no());
  }

  @Test
  void totalsAndRatesMatchTheDailyNumbers() {
    Report r = report("DEPARTMENT", "esdc");

    assertThat(r.targets()).containsExactly("ESDC");
    assertThat(r.daily()).hasSize(31);
    assertThat(r.pageViews())
        .isEqualTo(r.daily().stream().mapToLong(FeedbackReportService.DailyStats::pageViews).sum());
    assertThat(r.yesRate()).isEqualTo((double) r.yes() / (r.yes() + r.no()));
    assertThat(r.noRate()).isEqualTo((double) r.no() / (r.yes() + r.no()));
    assertThat(r.responseRate()).isEqualTo((double) (r.yes() + r.no()) / r.pageViews());
    assertThat(r.benchmarkYesRate()).isEqualTo(FeedbackReportService.BENCHMARK_YES_RATE);
  }

  @Test
  void theGcReportIgnoresTheValue() {
    Report r = report("GC", "anything");

    assertThat(r.scope()).isEqualTo(Scope.GC);
    assertThat(r.targets()).isEmpty();
  }

  @Test
  void theReportShowsTheMostRecentCommentsOnly() {
    Report r = report("GC", null);
    var request = service.parseRequest("GC", null, "2026-10-01", "2026-10-31");

    assertThat(r.commentCount()).isGreaterThan(FeedbackReportService.COMMENTS_SHOWN);
    assertThat(r.comments()).hasSize(FeedbackReportService.COMMENTS_SHOWN);
    assertThat(service.comments(request).count())
        .isEqualTo(Math.min(r.commentCount(), FeedbackReportService.MOCK_COMMENT_ROWS));
    assertThat(r.comments().getFirst().date()).isEqualTo("2026-10-31");
    assertThat(r.comments())
        .extracting(ReportComment::date)
        .allMatch(d -> d.compareTo("2026-10-01") >= 0 && d.compareTo("2026-10-31") <= 0);
  }

  @Test
  void aSingleDayIsAValidRange() {
    Report r = service.generate(service.parseRequest("PAGE", PAGE_A, "2026-10-05", "2026-10-05"));

    assertThat(r.daily()).hasSize(1);
    assertThat(r.comments()).allMatch(c -> c.date().equals("2026-10-05"));
  }

  @Test
  void assessmentComparesTheYesRateWithTheBenchmark() {
    assertThat(FeedbackReportService.assess(100, 0.80)).isEqualTo("ABOVE");
    assertThat(FeedbackReportService.assess(100, 0.70)).isEqualTo("NEAR");
    assertThat(FeedbackReportService.assess(100, 0.60)).isEqualTo("BELOW");
    assertThat(FeedbackReportService.assess(29, 0.99)).isEqualTo("INSUFFICIENT_DATA");
    assertThat(FeedbackReportService.assess(0, null)).isEqualTo("INSUFFICIENT_DATA");
  }

  @Test
  void invalidRequestsAreRejected() {
    for (List<String> args :
        List.of(
            List.of("", PAGE_A, "2026-10-01", "2026-10-31"),
            List.of("SITE", PAGE_A, "2026-10-01", "2026-10-31"),
            List.of("PAGE", " ", "2026-10-01", "2026-10-31"),
            List.of("PAGE", "canada.ca/en", "2026-10-01", "2026-10-31"),
            List.of("PAGE", "javascript:alert(1)", "2026-10-01", "2026-10-31"),
            List.of("DEPARTMENT", "XYZ", "2026-10-01", "2026-10-31"),
            List.of("PAGE", PAGE_A, "2026-10-31", "2026-10-01"),
            List.of("PAGE", PAGE_A, "2020-01-01", "2025-01-02"),
            List.of("PAGE", PAGE_A, "10/01/2026", "2026-10-31"),
            List.of("PAGE", PAGE_A, "", "2026-10-31"))) {
      assertThatThrownBy(
              () -> service.parseRequest(args.get(0), args.get(1), args.get(2), args.get(3)))
          .as(args.toString())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void aGroupHasAtMostFiftyUrls() {
    String fifty =
        String.join(
            "\n",
            java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> "https://www.canada.ca/" + i)
                .toList());

    assertThat(service.parseRequest("GROUP", fifty, "2026-10-01", "2026-10-01").targets())
        .hasSize(50);
    assertThatThrownBy(
            () ->
                service.parseRequest(
                    "GROUP", fifty + "\nhttps://www.canada.ca/50", "2026-10-01", "2026-10-01"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aFullYearIsAllowed() {
    var request = service.parseRequest("GC", null, "2024-01-01", "2024-12-31");

    assertThat(request.startDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(service.generate(request).daily()).hasSize(366);
  }

  @Test
  void mockDataCanSpanFiveYears() {
    LocalDate start = LocalDate.of(2021, 1, 1);
    LocalDate end = start.plusYears(FeedbackReportService.MAX_RANGE_YEARS);
    var request = service.parseRequest("PAGE", PAGE_A, start.toString(), end.toString());
    Report r = service.generate(request);

    assertThat(r.daily()).hasSize((int) ChronoUnit.DAYS.between(start, end) + 1);
    assertThat(r.daily().getFirst().date()).isEqualTo("2021-01-01");
    assertThat(r.daily().getLast().date()).isEqualTo("2026-01-01");
    assertThat(r.comments())
        .extracting(ReportComment::date)
        .allMatch(d -> d.compareTo("2021-01-01") >= 0 && d.compareTo("2026-01-01") <= 0);
  }
}
