# 请假系统全流程部署教程 (Step-by-Step Tutorial)

本教程将引导您从零开始，将请假管理系统部署到生产环境。

---

## 目录
1. [环境准备](#1-环境准备)
2. [数据库迁移](#2-数据库迁移)
3. [后端打包与服务化](#3-后端打包与服务化)
4. [前端构建与 Nginx 反向代理](#4-前端构建与-nginx-反向代理)
5. [安全增强 (HTTPS)](#5-安全增强-https)
6. [故障排查](#6-故障排查)

---

## 1. 环境准备

### 服务器端 (Ubuntu/Debian 示例)
运行以下命令安装基础软件：
```bash
sudo apt update
sudo apt install openjdk-17-jdk mysql-server nginx certbot python3-certbot-nginx -y
```

### 本地端
确保已安装 `Maven`、`Node.js` 和 `npm`。

---

## 2. 数据库迁移

### A. 本地导出 (Local Export)
在本地终端执行（替换您的数据库用户名和密码）：
```bash
mysqldump -u root -p leave_system > leave_system_backup.sql
```

### B. 上传并导入 (Server Import)
1. **上传文件到服务器**:
   ```bash
   scp leave_system_backup.sql root@<服务器IP>:/tmp/
   ```
2. **服务器端创建并导入**:
   ```bash
   sudo mysql -u root -p
   ```
   在 MySQL Shell 中执行：
   ```sql
   CREATE DATABASE leave_system CHARACTER SET utf8mb4;
   CREATE USER 'leave_admin'@'localhost' IDENTIFIED BY '您的强密码';
   GRANT ALL PRIVILEGES ON leave_system.* TO 'leave_admin'@'localhost';
   FLUSH PRIVILEGES;
   USE leave_system;
   SOURCE /tmp/leave_system_backup.sql;
   EXIT;
   ```

---

## 3. 后端打包与服务化

### A. 本地打包 (Packaging)
```bash
cd backend
mvn clean package -DskipTests
```
将产生的 `backend/target/backend-0.0.1-SNAPSHOT.jar` 上传：
```bash
scp backend-0.0.1-SNAPSHOT.jar root@<服务器IP>:/opt/leave-system/backend.jar
```

### B. 配置 Systemd 服务
在服务器上创建并编辑 `/etc/systemd/system/leave-backend.service`：
```ini
[Unit]
Description=Leave Management Backend Service
After=network.target

[Service]
User=root
WorkingDirectory=/opt/leave-system
ExecStart=/usr/bin/java -Xmx512M -jar backend.jar \
  --spring.datasource.url=jdbc:mysql://localhost:3306/leave_system \
  --spring.datasource.username=leave_admin \
  --spring.datasource.password=您的强密码 \
  --jwt.secret=您的超长随机密钥
Restart=always
RestartSec=10
StandardOutput=syslog
StandardError=syslog
SyslogIdentifier=leave-backend

[Install]
WantedBy=multi-user.target
```

### C. 启动
```bash
sudo systemctl daemon-reload
sudo systemctl enable leave-backend
sudo systemctl start leave-backend
# 查看状态
sudo systemctl status leave-backend
```

---

## 4. 前端构建与 Nginx 反向代理

### A. 本地构建与上传
```bash
cd frontend
npm install
npm run build
# 上传 dist 目录
scp -r dist/* root@<服务器IP>:/var/www/leave-system/
```

### B. Nginx 配置
创建 `/etc/nginx/conf.d/leave_system.conf`：
```nginx
server {
    listen 80;
    server_name 您的域名; # 如果没有域名请填 IP

    root /var/www/leave-system;
    index index.html;

    # 处理 SPA 路由
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 反向代理后端 API
    location /api/ {
        proxy_pass http://127.0.0.1:8080/; 
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```
测试并重启：
```bash
sudo nginx -t
sudo systemctl restart nginx
```

---

## 5. 安全增强 (HTTPS)

如果您有域名，强烈建议配置 SSL：
```bash
sudo certbot --nginx -d 您的域名
```
Certbot 会自动修改 Nginx 配置并设置自动续期。

---

## 6. 故障排查 (Troubleshooting)

| 问题 | 检查步骤 |
| :--- | :--- |
| **前端 404** | 检查 Nginx `root` 路径是否正确，是否有权限读取 `/var/www/leave-system`。 |
| **后端 502 Bad Gateway** | 说明后端没起来。运行 `journalctl -u leave-backend -f` 查看错误日志。 |
| **API 调用超时** | 检查服务器防火墙（UFW 或安全组）是否允许 80/443 端口。 |
| **数据库连接失败** | 检查 `spring.datasource.url` 是否正确，用户权限是否已刷新。 |

---

## 7. 维护命令

- **查看后端实时日志**: `journalctl -u leave-backend -f`
- **重启整个系统**: 
  ```bash
  sudo systemctl restart leave-backend
  sudo systemctl restart nginx
  ```

 bitumen
