(function () {
  var locale = document.documentElement.lang === "fr" ? "fr-CA" : "en-CA";
  var numberFormat = new Intl.NumberFormat(locale);
  var percentFormat = new Intl.NumberFormat(locale, { style: "percent", maximumFractionDigits: 1 });
  var chart = null;
  var noRateChart = null;
  var reportDaily = [];
  var commentsTable = null;
  var pending = null;

  function renderComments(rows) {
    var french = document.documentElement.lang === "fr";
    if (commentsTable) {
      commentsTable.clear();
      commentsTable.rows.add(rows);
      commentsTable.draw();
      commentsTable.columns.adjust();
      return;
    }
    // DataTable initialization
    commentsTable = new DataTable("#reportCommentsTable", {
      data: rows,
      columns: [
        { data: "date", width: "10%" },
        { data: "url", width: "30%", render: SafeRender.link },
        { data: "language", width: "8%" },
        { data: "comment", width: "52%" },
      ],
      language: french
        ? {
            url: "//cdn.datatables.net/plug-ins/2.3.2/i18n/fr-FR.json",
            lengthMenu: "Afficher _MENU_ entrées",
            info: "Affichage de _START_ à _END_ sur _TOTAL_ entrées",
            paginate: { first: "Premier", last: "Dernier", next: "Suivant", previous: "Précédent" },
          }
        : {
            lengthMenu: "Show _MENU_ entries",
            info: "Showing _START_ to _END_ of _TOTAL_ entries",
            paginate: { first: "First", last: "Last", next: "Next", previous: "Previous" },
          },
      stripeClasses: [],
      order: [[0, "desc"]],
      pageLength: 50,
      lengthMenu: [
        [10, 25, 50, 100],
        [10, 25, 50, 100],
      ],
      dom: 't<"table-controls-outside"lip>',
      initComplete: function () {
        // Move pagination controls outside the table wrapper
        var box = document.querySelector("#report-comments .feedback-tool-data");
        var controls = document.querySelector("#report-comments .table-controls-outside");
        if (box && controls) box.insertAdjacentElement("afterend", controls);
      },
    });
  }

  function byId(id) {
    return document.getElementById(id);
  }

  function formatNumber(value) {
    return numberFormat.format(value);
  }

  function formatRate(value) {
    return value == null ? "–" : percentFormat.format(value);
  }

  function setText(id, text) {
    byId(id).textContent = text;
  }

  function appendRow(tbody, cells) {
    var tr = document.createElement("tr");
    cells.forEach(function (cell) {
      var td = document.createElement("td");
      td.textContent = cell;
      tr.appendChild(td);
    });
    tbody.appendChild(tr);
  }

  function fillTable(id, rows, toCells) {
    var tbody = byId(id);
    tbody.replaceChildren();
    rows.forEach(function (row) {
      appendRow(tbody, toCells(row));
    });
  }

  function drawChart(daily) {
    var canvas = byId("report-chart");
    if (chart) chart.destroy();
    chart = new Chart(canvas.getContext("2d"), {
      type: "bar",
      data: {
        labels: daily.map(function (d) { return d.date; }),
        datasets: [
          { label: canvas.dataset.labelYes, data: daily.map(function (d) { return d.yes; }), backgroundColor: "#2a78d6" },
          { label: canvas.dataset.labelNo, data: daily.map(function (d) { return d.no; }), backgroundColor: "#eb6834" },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: "index", intersect: false },
        scales: { x: { stacked: true }, y: { stacked: true, beginAtZero: true } },
        plugins: { legend: { position: "bottom" } },
      },
    });
  }

  function dayNoRate(day) {
    var responses = day.yes + day.no;
    return responses ? day.no / responses : null;
  }

  function drawNoRateChart(daily) {
    var canvas = byId("report-no-rate-chart");
    if (noRateChart) noRateChart.destroy();
    noRateChart = new Chart(canvas.getContext("2d"), {
      type: "line",
      data: {
        labels: daily.map(function (d) { return d.date; }),
        datasets: [
          {
            label: canvas.dataset.label,
            data: daily.map(dayNoRate),
            borderColor: "#eb6834",
            backgroundColor: "#eb6834",
            tension: 0.2,
            spanGaps: false,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: "index", intersect: false },
        scales: {
          y: {
            beginAtZero: true,
            max: 1,
            ticks: { callback: function (value) { return percentFormat.format(value); } },
          },
        },
        plugins: {
          legend: { position: "bottom" },
          tooltip: {
            callbacks: {
              label: function (item) {
                var value = item.parsed.y;
                return item.dataset.label + ": " + (value == null ? "–" : percentFormat.format(value));
              },
            },
          },
        },
      },
    });
  }

  function render(report, form, query) {
    var status = byId("report-status");
    setText("report-scope", form.dataset.scopeLabel);
    setText(
      "report-period",
      status.dataset.period.replace("{start}", report.startDate).replace("{end}", report.endDate)
    );

    var targets = byId("report-targets");
    targets.replaceChildren();
    report.targets.forEach(function (target) {
      var li = document.createElement("li");
      li.textContent = target;
      targets.appendChild(li);
    });

    setText("stat-pageViews", formatNumber(report.pageViews));
    setText("stat-yes", formatNumber(report.yes));
    setText("stat-no", formatNumber(report.no));
    setText("stat-commentCount", formatNumber(report.commentCount));
    setText("stat-yesRate", formatRate(report.yesRate));
    setText("stat-noRate", formatRate(report.noRate));
    setText("stat-responseRate", formatRate(report.responseRate));
    setText("stat-benchmarkYesRate", formatRate(report.benchmarkYesRate));

    document.querySelectorAll("[data-assessment]").forEach(function (el) {
      el.hidden = el.dataset.assessment !== report.assessment;
    });

    reportDaily = report.daily;
    byId("clicks-timeline").value = "day";
    byId("no-rate-timeline").value = "day";
    setText("comments-shown", formatNumber(report.comments.length));
    setText("comments-total", formatNumber(report.commentCount));
    byId("download-csv").href = "/reports/comments/exportCSV?" + query;
    byId("download-excel").href = "/reports/comments/exportExcel?" + query;

    byId("report-results").hidden = false;
    byId("report-comments").hidden = false;
    byId("report-next").hidden = false;
    renderComments(report.comments);
    showClicks();
    showNoRate();
    status.textContent = status.dataset.done;
    byId("report-heading").focus();
  }

  function periodKey(isoDate, bucket) {
    if (bucket === "day") return isoDate;
    var parts = isoDate.split("-");
    var year = +parts[0];
    var month = +parts[1];
    if (bucket === "month") return isoDate.slice(0, 7);
    if (bucket === "quarter") {
      var quarter = Math.floor((month - 1) / 3) + 1;
      var mark = locale === "fr-CA" ? "T" : "Q";
      return year + "-" + mark + quarter;
    }
    if (bucket === "year") return String(year);
    var date = new Date(Date.UTC(year, month - 1, +parts[2]));
    var weekday = date.getUTCDay();
    date.setUTCDate(date.getUTCDate() - (weekday === 0 ? 6 : weekday - 1));
    return date.toISOString().slice(0, 10);
  }

  function groupDaily(bucket) {
    var groups = [];
    var index = {};
    reportDaily.forEach(function (day) {
      var key = periodKey(day.date, bucket);
      var group = index[key];
      if (!group) {
        group = { date: key, pageViews: 0, yes: 0, no: 0 };
        index[key] = group;
        groups.push(group);
      }
      group.pageViews += day.pageViews;
      group.yes += day.yes;
      group.no += day.no;
    });
    return groups;
  }

  function showClicks() {
    var select = byId("clicks-timeline");
    var rows = groupDaily(select.value);
    setText("clicks-period", select.options[select.selectedIndex].dataset.heading);
    fillTable("report-daily", rows, function (row) {
      var responses = row.yes + row.no;
      return [row.date, formatNumber(row.pageViews), formatNumber(row.yes), formatNumber(row.no),
        formatRate(responses ? row.yes / responses : null),
        formatRate(responses ? row.no / responses : null)];
    });
    drawChart(rows);
  }

  function showNoRate() {
    var select = byId("no-rate-timeline");
    var rows = groupDaily(select.value);
    setText("no-rate-period", select.options[select.selectedIndex].dataset.heading);
    fillTable("report-no-rate", rows, function (row) {
      return [row.date, formatRate(dayNoRate(row))];
    });
    drawNoRateChart(rows);
  }

  function generate(form) {
    var status = byId("report-status");
    var params = new URLSearchParams(new FormData(form));
    params.set("scope", form.dataset.scope);
    var query = params.toString();

    if (pending) pending.abort();
    pending = new AbortController();
    byId("report-error").hidden = true;
    status.textContent = status.dataset.loading;

    fetch("/reports/data?" + query, { signal: pending.signal, headers: { "X-Requested-With": "XMLHttpRequest" } })
      .then(function (response) {
        if (!response.ok) throw new Error("HTTP " + response.status);
        return response.json();
      })
      .then(function (report) {
        render(report, form, query);
      })
      .catch(function (error) {
        if (error.name === "AbortError") return;
        status.textContent = "";
        byId("report-results").hidden = true;
        byId("report-comments").hidden = true;
        byId("report-next").hidden = true;
        byId("report-error").hidden = false;
      });
  }

  // WET shows its own errors; the browser's check is the fallback
  document.addEventListener("submit", function (event) {
    var form = event.target.closest(".report-form");
    if (!form) return;
    event.preventDefault();
    if (form.checkValidity()) {
      generate(form);
    } else if (!form.closest(".wb-frmvld-inited")) {
      form.reportValidity();
    }
  });

  document.addEventListener("click", function (event) {
    var button = event.target.closest(".copy-prompt");
    if (!button || !navigator.clipboard) return;
    navigator.clipboard.writeText(byId(button.dataset.target).value).then(function () {
      button.textContent = button.dataset.copied;
    });
  });

  document.addEventListener("change", function (event) {
    if (event.target.id === "clicks-timeline") showClicks();
    if (event.target.id === "no-rate-timeline") showNoRate();
  });
})();
