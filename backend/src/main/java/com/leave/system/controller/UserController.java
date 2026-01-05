package com.leave.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leave.system.common.Result;
import com.leave.system.entity.SysUser;
import com.leave.system.mapper.SysUserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import com.leave.system.dto.ChangePasswordDTO;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/system/user")
public class UserController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserController.class);

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final com.leave.system.service.LeaveService leaveService;

    public UserController(SysUserMapper userMapper,
            PasswordEncoder passwordEncoder,
            com.leave.system.service.LeaveService leaveService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.leaveService = leaveService;
    }

    @GetMapping("/list")
    public Result<Page<SysUser>> list(@RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        Page<SysUser> page = userMapper.selectPage(new Page<>(current, size), null);
        page.getRecords().forEach(this::calculateSeniority);
        return Result.success(page);
    }

    @GetMapping("/all")
    public Result<List<SysUser>> all() {
        List<SysUser> users = userMapper.selectList(null);
        return Result.success(users);
    }

    @GetMapping("/{id}")
    public Result<SysUser> getById(@PathVariable Long id) {
        SysUser user = userMapper.selectById(id);
        if (user != null) {
            calculateSeniority(user);
            // 不返回密码
            user.setPassword(null);
        }
        return Result.success(user);
    }

    @PostMapping("/add")
    public Result<Void> add(@RequestBody SysUser user) {
        log.info("Adding user: {}", user);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setCreateTime(LocalDateTime.now());
        user.setDeleted(0);
        calculateSeniority(user);
        log.info("Calculated seniority: {}", user.getSocialSeniority());
        userMapper.insert(user);

        // 自动初始化当前年度的年假账户
        try {
            int currentYear = java.time.LocalDate.now().getYear();
            leaveService.initYearlyAccount(user.getId(), currentYear);
            log.info("✅ Auto-initialized leave account for user {} year {}", user.getUsername(), currentYear);
        } catch (Exception e) {
            log.warn("⚠️ Failed to auto-initialize leave account for user {}: {}", user.getUsername(), e.getMessage());
            // 不影响用户创建，只是记录警告
        }

        return Result.success(null, "用户添加成功");
    }

    @PutMapping("/update")
    public Result<Void> update(@RequestBody SysUser user) {
        user.setUpdateTime(LocalDateTime.now());

        // 如果密码不为空，则更新密码
        if (user.getPassword() != null && !user.getPassword().trim().isEmpty()) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            // 否则不更新密码字段
            user.setPassword(null);
        }

        calculateSeniority(user);
        userMapper.updateById(user);

        // Recalculate leave account for current year in case entry date or seniority
        // changed
        try {
            int currentYear = java.time.LocalDate.now().getYear();
            leaveService.initYearlyAccount(user.getId(), currentYear);
        } catch (Exception e) {
            log.warn("Failed to refresh leave account for user {}: {}", user.getId(), e.getMessage());
        }

        return Result.success(null, "用户更新成功");
    }

    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestBody ChangePasswordDTO dto) {
        // 获取当前用户ID (从SecurityContext获取或前端传参，这里简单处理，实际应从Token获取)
        // 假设当前用户ID在请求中并没有，但我们可以通过SecurityContext获取
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        SysUser user = userMapper.selectOne(new QueryWrapper<SysUser>().eq("username", username));
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 校验旧密码
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            return Result.error("旧密码错误");
        }

        // 校验两次新密码是否一致
        if (!dto.getNewPassword().equals(dto.getConfirmPassword())) {
            return Result.error("两次输入的新密码不一致");
        }

        // 更新密码
        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);

        return Result.success(null, "密码修改成功");
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userMapper.deleteById(id);
        return Result.success(null);
    }

    @PostMapping("/resign/{id}")
    public Result<Void> resign(@PathVariable Long id,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }
        user.setStatus("RESIGNED");
        if (body != null && body.containsKey("resignationDate")) {
            user.setResignationDate(LocalDate.parse(body.get("resignationDate")));
        } else {
            user.setResignationDate(LocalDate.now());
        }
        userMapper.updateById(user);

        // Soft delete leave accounts
        try {
            leaveService.deleteAccountsByUserId(id);
            log.info("Soft deleted leave accounts for resigned user {}", id);
        } catch (Exception e) {
            log.error("Failed to soft delete leave accounts for user {}", id, e);
        }

        return Result.success(null);
    }

    @PostMapping("/activate/{id}")
    public Result<Void> activate(@PathVariable Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }
        user.setStatus("ACTIVE");
        user.setResignationDate(null);
        userMapper.updateById(user);
        return Result.success(null);
    }

    /**
     * 批量导入用户
     */
    @PostMapping("/import")
    public Result<com.leave.system.dto.UserImportResult> importUsers(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {

        if (file.isEmpty()) {
            return Result.error("文件不能为空");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
            return Result.error("只支持CSV格式文件");
        }

        try {
            com.leave.system.dto.UserImportResult result = importUsersFromCsv(file);
            return Result.success(result);
        } catch (Exception e) {
            log.error("导入用户失败", e);
            return Result.error("导入失败: " + e.getMessage());
        }
    }

    /**
     * 从CSV文件导入用户
     */
    private com.leave.system.dto.UserImportResult importUsersFromCsv(
            org.springframework.web.multipart.MultipartFile file) throws Exception {

        com.leave.system.dto.UserImportResult result = new com.leave.system.dto.UserImportResult();

        // 使用BOMInputStream处理BOM字符
        try (java.io.InputStream inputStream = file.getInputStream();
                org.apache.commons.io.input.BOMInputStream bomInputStream = new org.apache.commons.io.input.BOMInputStream(
                        inputStream, false);
                java.io.Reader reader = new java.io.InputStreamReader(bomInputStream, "UTF-8")) {

            org.apache.commons.csv.CSVParser csvParser = org.apache.commons.csv.CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withIgnoreHeaderCase()
                    .withTrim()
                    .parse(reader);

            int lineNumber = 1;

            for (org.apache.commons.csv.CSVRecord record : csvParser) {
                lineNumber++;
                result.setTotalCount(result.getTotalCount() + 1);

                try {
                    String employeeNumber = record.get("工号");
                    String realName = record.get("姓名");
                    String entryDateStr = record.get("入职日期");
                    String firstWorkDateStr = record.get("首次参加工作日期");
                    String dingtalkUserId = record.get("钉钉ID");

                    // 验证必填字段
                    if (employeeNumber == null || employeeNumber.trim().isEmpty()) {
                        result.addError(lineNumber, "工号不能为空");
                        result.setFailureCount(result.getFailureCount() + 1);
                        continue;
                    }
                    if (realName == null || realName.trim().isEmpty()) {
                        result.addError(lineNumber, "姓名不能为空");
                        result.setFailureCount(result.getFailureCount() + 1);
                        continue;
                    }

                    // 检查工号是否已存在
                    QueryWrapper<SysUser> queryByNumber = new QueryWrapper<>();
                    queryByNumber.eq("employee_number", employeeNumber.trim());
                    if (userMapper.selectCount(queryByNumber) > 0) {
                        result.addError(lineNumber, "工号已存在: " + employeeNumber);
                        result.setFailureCount(result.getFailureCount() + 1);
                        continue;
                    }

                    // 生成用户名（姓名拼音）
                    String username = com.leave.system.util.PinyinUtil.toPinyin(realName.trim());

                    // 检查用户名是否重复，如果重复则添加数字后缀
                    String finalUsername = username;
                    int suffix = 1;
                    while (userMapper.selectCount(new QueryWrapper<SysUser>().eq("username", finalUsername)) > 0) {
                        finalUsername = username + suffix;
                        suffix++;
                    }

                    // 创建用户
                    SysUser user = new SysUser();
                    user.setEmployeeNumber(employeeNumber.trim());
                    user.setUsername(finalUsername);
                    user.setPassword(passwordEncoder.encode("123456")); // 默认密码
                    user.setRealName(realName.trim());
                    user.setRoleId(2L); // 默认员工角色
                    user.setStatus("ACTIVE");

                    if (dingtalkUserId != null && !dingtalkUserId.trim().isEmpty()) {
                        user.setDingtalkUserId(dingtalkUserId.trim());
                    }

                    // 解析日期 - 支持多种格式
                    if (entryDateStr != null && !entryDateStr.trim().isEmpty()) {
                        user.setEntryDate(parseDate(entryDateStr.trim()));
                    }
                    if (firstWorkDateStr != null && !firstWorkDateStr.trim().isEmpty()) {
                        user.setFirstWorkDate(parseDate(firstWorkDateStr.trim()));
                    }

                    // 保存用户
                    userMapper.insert(user);
                    result.setSuccessCount(result.getSuccessCount() + 1);

                    // 自动初始化当前年度的年假账户
                    try {
                        int currentYear = java.time.LocalDate.now().getYear();
                        leaveService.initYearlyAccount(user.getId(), currentYear);
                        log.info("✅ 导入用户成功并初始化账户: {} ({})", realName, finalUsername);
                    } catch (Exception accountException) {
                        log.warn("⚠️ 用户{}导入成功，但账户初始化失败: {}", finalUsername, accountException.getMessage());
                        // 账户初始化失败不影响用户导入
                    }

                } catch (Exception e) {
                    result.addError(lineNumber, e.getMessage());
                    result.setFailureCount(result.getFailureCount() + 1);
                    log.error("导入第{}行失败", lineNumber, e);
                }
            }
        }

        return result;
    }

    /**
     * 解析日期 - 支持多种格式
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }

        // 尝试多种日期格式
        String[] formats = {
                "yyyy-M-d", // 2024-1-15 or 2024-01-15
                "yyyy/M/d", // 2024/1/15 or 2024/01/15
                "yyyy.M.d", // 2024.1.15 or 2024.01.15
                "yyyyMMdd" // 20240115
        };

        for (String format : formats) {
            try {
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern(format);
                return LocalDate.parse(dateStr, formatter);
            } catch (Exception e) {
                // 尝试下一个格式
            }
        }

        // 如果都失败，抛出异常
        throw new IllegalArgumentException(
                "无法解析日期: " + dateStr + "，支持的格式：yyyy-MM-dd, yyyy/MM/dd, yyyy.MM.dd, yyyyMMdd");
    }

    private void calculateSeniority(SysUser user) {
        if (user.getFirstWorkDate() != null) {
            LocalDate now = LocalDate.now();
            java.time.Period period = java.time.Period.between(user.getFirstWorkDate(), now);
            user.setSocialSeniority(period.getYears());
        } else {
            user.setSocialSeniority(0);
        }
    }
}
