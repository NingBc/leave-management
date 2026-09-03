#!/bin/bash

# Configuration
FRONTEND_DIR="frontend"
BACKEND_DIR="backend"
STATIC_DIR="$BACKEND_DIR/src/main/resources/static"

echo "=================================================="
echo "   Building Fullstack Leave System (All-in-One)   "
echo "=================================================="

# 1. Build Frontend
echo "[1/4] Building Frontend..."
cd $FRONTEND_DIR
# 用 npm ci 而不是 npm install。install 的职责本来就包含"必要时改写 lock",
# 而不同 npm 版本对 peer 依赖的标注规则不一样(package.json 里也没有
# engines / packageManager 约束), 于是每跑一次构建, package-lock.json
# 上就多出一片只改 "peer": true、与依赖版本毫无关系的噪音 diff,
# 谁的 npm 版本不同就来回翻烙饼。
# ci 严格按 lock 安装, 从不回写, 跨机器产物一致。
npm ci
if [ $? -ne 0 ]; then
    echo "Error: Frontend build failed."
    exit 1
fi
cd ..

# 2. Prepare Backend Static Resources
echo "[2/4] Copying frontend assets to backend..."
# Create directory if it doesn't exist
mkdir -p $STATIC_DIR
# Clear old files
rm -rf $STATIC_DIR/*
# Copy new files
cp -r $FRONTEND_DIR/dist/* $STATIC_DIR/

# 3. Build Backend
echo "[3/4] Building Backend JAR..."
cd $BACKEND_DIR
mvn clean package -DskipTests
if [ $? -ne 0 ]; then
    echo "Error: Backend build failed."
    exit 1
fi
cd ..

# 4. Success
JAR_PATH="$BACKEND_DIR/target/leave-management.jar"
echo "=================================================="
echo "   Build Success!                                 "
echo "=================================================="
echo "Executable JAR is located at:"
echo "  $JAR_PATH"
echo ""
echo "To run the application:"
echo "  java -jar $JAR_PATH"
echo "=================================================="
