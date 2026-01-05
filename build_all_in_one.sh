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
npm install
npm run build
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
JAR_PATH="$BACKEND_DIR/target/backend-0.0.1-SNAPSHOT.jar"
echo "=================================================="
echo "   Build Success!                                 "
echo "=================================================="
echo "Executable JAR is located at:"
echo "  $JAR_PATH"
echo ""
echo "To run the application:"
echo "  java -jar $JAR_PATH"
echo "=================================================="
