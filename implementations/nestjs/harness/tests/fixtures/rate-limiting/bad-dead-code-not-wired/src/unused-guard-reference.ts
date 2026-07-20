// APP_GUARD와 ThrottlerGuard라는 단어가 파일 어딘가에 존재하기만 해도 통과하던 예전(문자열 매칭)
// 검사를 우회하던 dead code 시나리오 — 실제로는 어떤 @Module의 providers에도, 어떤 컨트롤러의
// @UseGuards()에도 연결되지 않은 미사용 import/주석이다.
// APP_GUARD, ThrottlerGuard
export const NOTE = 'ThrottlerGuard와 APP_GUARD는 여기 텍스트로만 존재하고 실제로 연결되지 않았다'
