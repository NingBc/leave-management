package com.leave.system.controller;

import com.leave.system.service.DingTalkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dingtalk")
public class DingTalkController {

    @Autowired
    private DingTalkService dingTalkService;

    @PostMapping("/sync")
    public String syncLeaveData() {
        dingTalkService.syncLeaveData();
        return "Sync triggered successfully";
    }
}
