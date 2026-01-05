package com.leave.system.controller;

import com.leave.system.common.Result;
import com.leave.system.config.DingTalkAppConfig;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.SysUserMapper;
import com.leave.system.security.JwtUtils;
import com.leave.system.service.DingTalkService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "*")
@Slf4j
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final SysUserMapper userMapper;
    private final DingTalkService dingTalkService;
    private final DingTalkAppConfig dingTalkAppConfig;

    public AuthController(AuthenticationManager authenticationManager, JwtUtils jwtUtils,
            SysUserMapper userMapper,
            DingTalkService dingTalkService,
            DingTalkAppConfig dingTalkAppConfig) {
        this.authenticationManager = authenticationManager;
        this.jwtUtils = jwtUtils;
        this.userMapper = userMapper;
        this.dingTalkService = dingTalkService;
        this.dingTalkAppConfig = dingTalkAppConfig;
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String token = jwtUtils.generateToken(userDetails);

        SysUser user = userMapper.selectByUsername(request.getUsername());

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        if (user != null) {
            response.put("userId", user.getId());
            response.put("username", user.getUsername());
        }
        return Result.success(response, "登录成功");
    }

    @PostMapping("/dingtalk/login")
    public Result<Map<String, Object>> dingtalkLogin(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        if (code == null || code.isEmpty()) {
            return Result.error("AuthCode不能为空");
        }

        // 1. Get DingTalk userid from code
        String dingtalkUserId = dingTalkService.getUseridByAuthCode(code);

        // 2. Find user by dingtalk_userid
        SysUser user = userMapper.selectByDingtalkUserId(dingtalkUserId);

        if (user == null) {
            return Result.error("未找到此钉钉账号对应的系统用户");
        }

        // 3. Generate JWT
        // We bypass standard authentication as the trust is inherited from DingTalk
        UserDetails userDetails = User
                .withUsername(user.getUsername())
                .password("") // Password not needed for token generation
                .authorities(new ArrayList<>())
                .build();

        String token = jwtUtils.generateToken(userDetails);

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("userId", user.getId());
        response.put("username", user.getUsername());

        return Result.success(response, "钉钉免密登录成功");
    }

    @GetMapping("/config/dingtalk")
    public Result<Map<String, String>> getDingTalkConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("corpId", dingTalkAppConfig.getCorpId());
        // Do NOT return appSecret here for security
        return Result.success(config);
    }

    @Data
    static class LoginRequest {
        private String username;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
