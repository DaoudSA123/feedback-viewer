package ca.gc.tbs.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ca.gc.tbs.service.FeedbackReportService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class FeedbackReportControllerTest {

  private FeedbackReportController controller;
  private MockHttpServletRequest request;
  private MockHttpSession session;

  @BeforeEach
  void setUp() {
    controller = new FeedbackReportController(new FeedbackReportService());
    session = new MockHttpSession();
    session.setAttribute("lang", "en");
    request = new MockHttpServletRequest();
    request.setSession(session);
    request.setParameter("scope", "PAGE");
    request.setParameter("value", "https://www.canada.ca/en/a.html");
    request.setParameter("startDate", "2026-10-01");
    request.setParameter("endDate", "2026-10-31");
  }

  @Test
  void thePageUsesTheSessionLanguage() {
    session.setAttribute("lang", "fr");

    var mav = controller.reportPage(request);

    assertThat(mav.getViewName()).isEqualTo("feedbackReport_fr");
    assertThat(mav.getModel().get("departments")).isEqualTo(FeedbackReportService.DEPARTMENTS);
    assertThat(mav.getModel().get("defaultEndDate")).isEqualTo(LocalDate.now().minusDays(1));
  }

  @Test
  void dataReturnsTheReport() {
    var response = controller.reportData(request);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isInstanceOf(FeedbackReportService.Report.class);
  }

  @Test
  void invalidDataRequestsAreA400WithTheReason() {
    request.setParameter("endDate", "2026-09-01");

    var response = controller.reportData(request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).isEqualTo(Map.of("error", "startDate is after endDate"));
  }

  @Test
  void csvHasTheCommentsWithAHeaderAndALocalizedFileName() throws Exception {
    session.setAttribute("lang", "fr");
    var response = new MockHttpServletResponse();

    controller.exportCSV(request, response);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Content-Disposition"))
        .isEqualTo(
            "attachment; filename*=UTF-8''Rapport_retroaction_commentaires-"
                + LocalDate.now()
                + ".csv");
    String csv = response.getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv).startsWith("\uFEFFDate,URL,Language,Comment\n\"2026-10-31\",");
    assertThat(csv).contains("\"https://www.canada.ca/en/a.html\"");
  }

  @Test
  void excelIsAnXlsxDownload() throws Exception {
    var response = new MockHttpServletResponse();

    controller.exportExcel(request, response);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType())
        .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    assertThat(response.getContentAsByteArray()).isNotEmpty();
  }

  @Test
  void invalidExportRequestsAreA400() throws Exception {
    request.setParameter("scope", "SITE");
    var response = new MockHttpServletResponse();

    controller.exportCSV(request, response);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).isEmpty();
  }
}
