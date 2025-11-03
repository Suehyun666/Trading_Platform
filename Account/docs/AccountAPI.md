Account API

// 공통 응답 코드
enum ResultCode {
  SUCCESS = 0;
  INSUFFICIENT_FUNDS = 1;
  ACCOUNT_NOT_FOUND = 2;
  INTERNAL_ERROR = 3;
  INVALID_REQUEST = 4;
  ACCOUNT_SUSPENDED = 5;
  DUPLICATE_REQUEST = 6;
}

1. reserve
message ReserveRequest {
  int64 account_id = 1;
  string amount = 2;  // BigDecimal로 파싱 (double은 부동소수점 오차 발생)
  string request_id = 3;  // 멱등성 보장용 UUID
}

message ReserveReply {
  ResultCode code = 1;
  string message = 2;
}

2. unreserve
message UnreserveRequest {
  int64 account_id = 1;
  string amount = 2;  // BigDecimal로 파싱
  string request_id = 3;  // 멱등성 보장용 UUID
}

message UnreserveReply {
  ResultCode code = 1;
  string message = 2;
}


service AccountService {
  rpc Reserve(ReserveRequest) returns (ReserveReply);
  rpc Unreserve(UnreserveRequest) returns (UnreserveReply);
}
