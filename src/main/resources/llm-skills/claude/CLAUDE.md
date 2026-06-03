# {{projectName}} - Claude Code 설정

## 프로젝트 개요
- 언어: {{language}}
- 작성자: {{author}}

## 코드 스타일
- 불필요한 주석 금지 — 코드가 자체 문서화되어야 함
- 기존 파일 수정 우선, 새 파일 최소 생성
- 요청 범위 외 리팩토링 금지
- 에러 처리는 시스템 경계에서만

## 커밋 규칙
- 명령형 동사로 시작 (Add, Fix, Update, Remove)
- 50자 이내 제목
- Body는 WHY 위주로 작성

## 금지 사항
- `--no-verify` 로 훅 우회 금지
- force push 금지 (main/master 브랜치)
- 비밀키/토큰을 코드에 하드코딩 금지

## UTF-8 인코딩
- 모든 파일 UTF-8 인코딩 준수
