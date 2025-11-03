#!/bin/bash
# 전제조건 체크 스크립트

echo "========================================="
echo "OrderTest 전제조건 체크"
echo "========================================="
echo ""

ALL_OK=true

# 1. Redis 체크
echo -n "1. Redis (포트 6379): "
if redis-cli ping &> /dev/null; then
    echo "✅ 실행 중"
else
    echo "❌ 실행 안 됨"
    echo "   시작: redis-server"
    ALL_OK=false
fi

# 2. PostgreSQL 체크
echo -n "2. PostgreSQL (포트 5432): "
if pg_isready -h localhost -p 5432 &> /dev/null; then
    echo "✅ 실행 중"
else
    echo "❌ 실행 안 됨"
    echo "   시작: sudo systemctl start postgresql"
    ALL_OK=false
fi

# 3. Account 서버 체크 (gRPC 포트 8081)
echo -n "3. Account 서버 (포트 8081): "
if lsof -i :8081 &> /dev/null || netstat -tln 2>/dev/null | grep -q ":8081 "; then
    echo "✅ 실행 중"
else
    echo "❌ 실행 안 됨"
    echo "   시작: cd ~/Desktop/Account && ./gradlew run"
    ALL_OK=false
fi

# 4. Server 체크 (포트 8080)
echo -n "4. Server (포트 8080): "
if lsof -i :8080 &> /dev/null || netstat -tln 2>/dev/null | grep -q ":8080 "; then
    echo "✅ 실행 중"
else
    echo "❌ 실행 안 됨"
    echo "   시작: cd ~/Desktop/Server && ./gradlew run"
    ALL_OK=false
fi

echo ""
echo "========================================="
if [ "$ALL_OK" = true ]; then
    echo "✅ 모든 전제조건 충족!"
    echo "테스트 실행: ./run_single_test.sh"
else
    echo "❌ 일부 서비스가 실행되지 않았습니다."
    echo "위 안내에 따라 서비스를 시작하세요."
    exit 1
fi
echo "========================================="
