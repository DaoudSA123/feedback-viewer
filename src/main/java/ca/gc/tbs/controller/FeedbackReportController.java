package ca.gc.tbs.controller;

import ca.gc.tbs.service.FeedbackReportService;
import ca.gc.tbs.service.FeedbackReportService.ReportComment;
import ca.gc.tbs.service.FeedbackReportService.ReportRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class FeedbackReportController {

  private static final String[] EXPORT_COLUMNS = {"Date", "URL", "Language", "Comment"};

  private final FeedbackReportService reportService;

  public FeedbackReportController(FeedbackReportService reportService) {
    this.reportService = reportService;
  }

  @GetMapping("/reports")
  public ModelAndView reportPage(HttpServletRequest request) {
    String lang = (String) request.getSession().getAttribute("lang");
    var today = LocalDate.now();
    var mav = new ModelAndView("feedbackReport_" + lang);
    mav.addObject("lang", lang);
    mav.addObject("departments", FeedbackReportService.DEPARTMENTS);
    mav.addObject("defaultStartDate", today.minusDays(30));
    mav.addObject("defaultEndDate", today.minusDays(1));
    mav.addObject("maxDate", today);
    return mav;
  }

  @GetMapping("/reports/data")
  @ResponseBody
  public ResponseEntity<?> reportData(HttpServletRequest request) {
    try {
      return ResponseEntity.ok(reportService.generate(parseRequest(request)));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/reports/comments/exportCSV")
  public void exportCSV(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    ReportRequest reportRequest = parseOrReject(request, response);
    if (reportRequest == null) return;
    String filename = buildExportFilename(request, ".csv");
    commentsExport()
        .writeCsv(
            () -> reportService.comments(reportRequest),
            "attachment; filename*=UTF-8''" + filename,
            request,
            response);
  }

  @GetMapping("/reports/comments/exportExcel")
  public void exportExcel(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    ReportRequest reportRequest = parseOrReject(request, response);
    if (reportRequest == null) return;
    String filename = buildExportFilename(request, ".xlsx");
    commentsExport()
        .writeExcel(
            () -> reportService.comments(reportRequest),
            "attachment; filename=\"" + filename + "\"",
            "Comments",
            response);
  }

  private ReportRequest parseRequest(HttpServletRequest request) {
    return reportService.parseRequest(
        request.getParameter("scope"),
        request.getParameter("value"),
        request.getParameter("startDate"),
        request.getParameter("endDate"));
  }

  private ReportRequest parseOrReject(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      return parseRequest(request);
    } catch (IllegalArgumentException e) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
      return null;
    }
  }

  private static StreamingExport<ReportComment> commentsExport() {
    return new StreamingExport<>(
        "Feedback report comments",
        EXPORT_COLUMNS,
        c -> new Object[] {c.date(), c.url(), c.language(), c.comment()},
        FeedbackReportController::sanitizeCsvValue,
        StreamingExport.WhenEmpty.HEADER_ONLY);
  }

  // Keeps spreadsheet apps from running a value as a formula; StreamingExport quotes it afterwards
  private static String sanitizeCsvValue(String value) {
    String sanitized = value.replace("\t", " ").replace("\r", "");
    if (!sanitized.isEmpty()) {
      char firstChar = sanitized.charAt(0);
      if (firstChar == '=' || firstChar == '+' || firstChar == '-' || firstChar == '@') {
        sanitized = "'" + sanitized;
      }
    }
    return sanitized;
  }

  private static String buildExportFilename(HttpServletRequest request, String extension) {
    String lang = (String) request.getSession().getAttribute("lang");
    String prefix =
        "fr".equalsIgnoreCase(lang)
            ? "Rapport_retroaction_commentaires-"
            : "Feedback_report_comments-";
    return prefix + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + extension;
  }
}
