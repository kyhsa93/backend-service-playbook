package outbox

// 의도적으로 Writer/Relay 타입을 선언하지 않는 fixture — shared-infra 규칙이
// outbox/ 디렉토리 존재만으로 통과시키지 않고 실제 타입 선언을 확인하는지 검증한다.
type SomethingElse struct{}
