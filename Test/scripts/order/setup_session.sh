#!/bin/bash
# Redis에 테스트용 세션 생성

ACCOUNT_ID=1000
SESSION_ID=1000  # OrderClient에서 sessionId = accountId 사용

echo "========================================="
echo "Redis에 테스트 세션 생성"
echo "========================================="
echo ""
echo "Account ID: $ACCOUNT_ID"
echo "Session ID: $SESSION_ID"
echo ""

# Redis에 세션 생성 (24시간 TTL)
redis-cli SETEX "session:$SESSION_ID" 86400 "$ACCOUNT_ID"

# 확인
RESULT=$(redis-cli GET "session:$SESSION_ID")

if [ "$RESULT" = "$ACCOUNT_ID" ]; then
    echo "✅ 세션 생성 성공!"
    echo ""
    echo "확인: redis-cli GET session:$SESSION_ID"
    echo "결과: $RESULT"
else
    echo "❌ 세션 생성 실패"
    exit 1
fi

echo ""
echo "========================================="
echo "이제 테스트를 실행하세요:"
echo "  ./run_single_test.sh"
echo "========================================="
