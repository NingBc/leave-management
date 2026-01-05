package com.leave.system.controller;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Handles SPA routing by forwarding 404 errors to index.html
 */
@Controller
public class SpaErrorController implements ErrorController {

    @RequestMapping("/error")
    public String handleError() {
        // Forward to index.html for client-side routing
        return "forward:/index.html";
    }
}
