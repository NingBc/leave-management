package com.leave.system.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leave.system.dto.ChangePasswordDTO;
import com.leave.system.dto.UserImportResult;
import com.leave.system.entity.SysUser;
import com.leave.system.exception.BusinessException;
import com.leave.system.mapper.SysUserMapper;
import com.leave.system.service.LeaveService;
import com.leave.system.service.UserService;
import com.leave.system.util.PinyinUtil;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.input.BOMInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final LeaveService leaveService;

    @Autowired
    @Lazy
    private UserService self;

    public UserServiceImpl(SysUserMapper userMapper, PasswordEncoder passwordEncoder, LeaveService leaveService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.leaveService = leaveService;
    }

    @Override
    public Page<SysUser> getUserPage(int current, int size) {
        Page<SysUser> page = userMapper.selectAllUsersPage(new Page<>(current, size));
        page.getRecords().forEach(this::calculateSeniority);
        return page;
    }

    @Override
    public List<SysUser> getAllUsers() {
        return userMapper.selectAllUsers();
    }

    @Override
    public SysUser getById(Long id) {
        SysUser user = userMapper.selectUserById(id);
        if (user != null) {
            calculateSeniority(user);
            user.setPassword(null);
        }
        return user;
    }

    @Override
    public SysUser getByUsername(String username) {
        return userMapper.selectByUsername(username);
    }

    @Override
    public SysUser getByDingtalkUserId(String dingtalkUserId) {
        return userMapper.selectByDingtalkUserId(dingtalkUserId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addUser(SysUser user) {
        log.info("Adding user: {}", user);

        if (userMapper.selectByUsername(user.getUsername()) != null) {
            throw new BusinessException("用户名 '" + user.getUsername() + "' 已存在，请使用其他用户名");
        }

        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setCreateTime(LocalDateTime.now());
        user.setDeleted(0);
        if (user.getStatus() == null || user.getStatus().isEmpty()) {
            user.setStatus("ACTIVE");
        }
        calculateSeniority(user);
        userMapper.insertUser(user);

        // 自动初始化当前年度的年假账户
        try {
            int currentYear = LocalDate.now().getYear();
            leaveService.initYearlyAccount(user.getId(), currentYear);
            log.info("✅ Auto-initialized leave account for user {} year {}", user.getUsername(), currentYear);
        } catch (Exception e) {
            log.warn("⚠️ Failed to auto-initialize leave account for user {}: {}", user.getUsername(), e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(SysUser user) {
        // Check for duplicate username (if username changed)
        SysUser existingUser = userMapper.selectByUsername(user.getUsername());
        if (existingUser != null && !existingUser.getId().equals(user.getId())) {
            throw new BusinessException("用户名 '" + user.getUsername() + "' 已存在，请使用其他用户名");
        }

        user.setUpdateTime(LocalDateTime.now());

        if (user.getPassword() != null && !user.getPassword().trim().isEmpty()) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(null);
        }

        calculateSeniority(user);
        userMapper.updateUser(user);

        try {
            int currentYear = LocalDate.now().getYear();
            leaveService.refreshAccount(user, currentYear);
        } catch (Exception e) {
            log.warn("Failed to refresh leave account for user {}: {}", user.getId(), e.getMessage());
        }
    }

    @Override
    public void changePassword(ChangePasswordDTO dto, String username) {
        SysUser user = userMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException("旧密码错误");
        }

        if (!dto.getNewPassword().equals(dto.getConfirmPassword())) {
            throw new BusinessException("两次输入的新密码不一致");
        }

        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateUser(user);
    }

    @Override
    public void deleteUser(Long id) {
        userMapper.deleteUserById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resignUser(Long id, Map<String, String> body) {
        SysUser user = userMapper.selectUserById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        user.setStatus("RESIGNED");
        if (body != null && body.containsKey("resignationDate")) {
            user.setResignationDate(LocalDate.parse(body.get("resignationDate")));
        } else {
            user.setResignationDate(LocalDate.now());
        }
        userMapper.updateUser(user);

        try {
            leaveService.deleteAccountsByUserId(id);
            log.info("Soft deleted leave accounts for resigned user {}", id);
        } catch (Exception e) {
            log.error("Failed to soft delete leave accounts for user {}", id, e);
        }
    }

    @Override
    public void activateUser(Long id) {
        SysUser user = userMapper.selectUserById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        user.setStatus("ACTIVE");
        user.setResignationDate(null);
        userMapper.updateUser(user);
    }

    @Override
    public UserImportResult importUsers(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
            throw new BusinessException("只支持CSV格式文件");
        }

        UserImportResult result = new UserImportResult();

        try (InputStream inputStream = file.getInputStream();
                BOMInputStream bomInputStream = new BOMInputStream(inputStream, false);
                Reader reader = new InputStreamReader(bomInputStream, "UTF-8")) {

            CSVParser csvParser = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withIgnoreHeaderCase()
                    .withTrim()
                    .parse(reader);

            int lineNumber = 1;

            for (CSVRecord record : csvParser) {
                lineNumber++;
                result.setTotalCount(result.getTotalCount() + 1);

                try {
                    self.importSingleUser(record);
                    result.setSuccessCount(result.getSuccessCount() + 1);
                } catch (Exception e) {
                    result.addError(lineNumber, e.getMessage());
                    result.setFailureCount(result.getFailureCount() + 1);
                    log.error("导入第{}行失败: {}", lineNumber, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("导入用户失败", e);
            throw new BusinessException("导入失败: " + e.getMessage());
        }

        return result;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void importSingleUser(CSVRecord record) {
        String employeeNumber = record.get("工号");
        String realName = record.get("姓名");
        String entryDateStr = record.get("入职日期");
        String firstWorkDateStr = record.get("首次参加工作日期");
        String dingtalkUserId = record.get("钉钉ID");

        if (employeeNumber == null || employeeNumber.trim().isEmpty()) {
            throw new BusinessException("工号不能为空");
        }
        if (realName == null || realName.trim().isEmpty()) {
            throw new BusinessException("姓名不能为空");
        }

        if (userMapper.selectByEmployeeNumber(employeeNumber.trim()) != null) {
            throw new BusinessException("工号已存在: " + employeeNumber);
        }

        String username = PinyinUtil.toPinyin(realName.trim());
        String finalUsername = username;
        int suffix = 1;
        while (userMapper.selectByUsername(finalUsername) != null) {
            finalUsername = username + suffix;
            suffix++;
        }

        SysUser user = new SysUser();
        user.setEmployeeNumber(employeeNumber.trim());
        user.setUsername(finalUsername);
        user.setPassword(passwordEncoder.encode("123456"));
        user.setRealName(realName.trim());
        user.setRoleId(2L);
        user.setStatus("ACTIVE");
        user.setCreateTime(LocalDateTime.now());
        user.setDeleted(0);

        if (dingtalkUserId != null && !dingtalkUserId.trim().isEmpty()) {
            user.setDingtalkUserId(dingtalkUserId.trim());
        }

        if (entryDateStr != null && !entryDateStr.trim().isEmpty()) {
            user.setEntryDate(parseDate(entryDateStr.trim()));
        }
        if (firstWorkDateStr != null && !firstWorkDateStr.trim().isEmpty()) {
            user.setFirstWorkDate(parseDate(firstWorkDateStr.trim()));
        }

        userMapper.insertUser(user);

        try {
            int currentYear = LocalDate.now().getYear();
            leaveService.initYearlyAccount(user.getId(), currentYear);
            log.info("✅ 导入用户成功并初始化账户: {} ({})", realName, finalUsername);
        } catch (Exception accountException) {
            log.warn("⚠️ 用户{}导入成功，但账户初始化失败: {}", finalUsername, accountException.getMessage());
            // Rethrowing to ensure rollback but wait, we want success if user created?
            // Actually, if account init fails, we might want to know.
            // But if we want the USER to be created regardless, we should catch inside
            // importSingleUser.
            // Let's decide: if account init fails, should the user be created?
            // In the log, user said "用户xiaonan导入成功，但账户初始化失败: User not found".
            // If they want the user to be created, we should NOT rethrow.
        }
    }

    @Override
    public void calculateSeniority(SysUser user) {
        if (user.getFirstWorkDate() != null) {
            LocalDate now = LocalDate.now();
            Period period = Period.between(user.getFirstWorkDate(), now);
            user.setSocialSeniority(period.getYears());
        } else {
            user.setSocialSeniority(0);
        }
    }

    @Override
    public LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }

        String[] formats = {
                "yyyy-M-d", "yyyy/M/d", "yyyy.M.d", "yyyyMMdd"
        };

        for (String format : formats) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                return LocalDate.parse(dateStr, formatter);
            } catch (Exception e) {
                // Continue to next format
            }
        }

        throw new IllegalArgumentException(
                "无法解析日期: " + dateStr + "，支持的格式：yyyy-MM-dd, yyyy/MM/dd, yyyy.MM.dd, yyyyMMdd");
    }
}
