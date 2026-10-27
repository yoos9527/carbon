package com.carbon.modules.invokelog.stats;

import com.carbon.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 看板接口。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * @param days 趋势窗口天数，1~30，默认 7。超出范围在服务里夹紧而不是报错——
     *             看板只是展示，不该因为用户手改 URL 就白屏
     */
    @GetMapping("/overview")
    public R<StatsOverview> overview(@RequestParam(defaultValue = "7") int days) {
        return R.ok(statsService.overview(days));
    }
}
