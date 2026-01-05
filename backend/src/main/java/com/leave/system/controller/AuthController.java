package com.leave.system.controller;

import com.leave.system.common.Result;
import com.leave.system.security.JwtUtils;
import lombok.Data;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@org.springframework.web.bind.annotation.CrossOrigin(origins = "*")
@lombok.extern.slf4j.Slf4j
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final com.leave.system.mapper.SysUserMapper userMapper;
    private final com.leave.system.service.DingTalkService dingTalkService;
    private final com.leave.system.config.DingTalkAppConfig dingTalkAppConfig;

    public AuthController(AuthenticationManager authenticationManager, JwtUtils jwtUtils,
            com.leave.system.mapper.SysUserMapper userMapper,
            com.leave.system.service.DingTalkService dingTalkService,
            com.leave.system.config.DingTalkAppConfig dingTalkAppConfig) {
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

        com.leave.system.entity.SysUser user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.leave.system.entity.SysUser>()
                        .eq("username", request.getUsername()));

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
        com.leave.system.entity.SysUser user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.leave.system.entity.SysUser>()
                        .eq("dingtalk_userid", dingtalkUserId));

        if (user == null) {
            return Result.error("未找到此钉钉账号对应的系统用户");
        }

        // 3. Generate JWT
        // We bypass standard authentication as the trust is inherited from DingTalk
        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password("") // Password not needed for token generation
                .authorities(new java.util.ArrayList<>())
                .build();

        String token = jwtUtils.generateToken(userDetails);

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("userId", user.getId());
        response.put("username", user.getUsername());

        return Result.success(response, "钉钉免密登录成功");
    }

    @org.springframework.web.bind.annotation.GetMapping("/config/dingtalk")
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
