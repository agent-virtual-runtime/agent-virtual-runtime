package com.avr.examples;

import com.avr.api.AgentResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Spring 股票报告示例的 HTTP 接口。 */
@RestController
@RequestMapping("/api/reports")
public class StockReportController {
    private final StockReportService reportService;

    public StockReportController(StockReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/stock")
    public Map<String, Object> generateStockReport() {
        AgentResult result = reportService.generateReport();
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("runId", result.getRunId());
        response.put("state", result.getState().name());
        response.put("message", result.getText());
        response.put("artifacts", result.getArtifacts().size());
        response.put("report", "/report/report.html");
        return response;
    }
}
