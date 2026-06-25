# 위키 서비스별 워크스페이스 설정 (완료)

> 구현 브랜치: `claude/tag-build-failure-cwh073`  
> 관련 문서: [wiki-agent 통합](./llm-wiki-agent-통합계획.md) · [플러그인-서비스 연동](../llm-manager/plugin-service-linking.md)

## 배경

기존에는 위키 워크스페이스 경로가 플러그인 전역 설정(`wiki.defaultCwd`)에 단 하나만 저장되었다.
사용자가 wiki-mcp 서비스를 여러 개 등록하면 모두 같은 워크스페이스를 가리키게 되어
서비스별 독립 운용이 불가능했다.

## 목표

- 서비스마다 독립 워크스페이스를 가질 수 있게 한다
- `wiki.defaultCwd`는 신규 서비스 등록 시 초기값 주입에만 쓰고, 등록 후에는 건드리지 않는다
- 플러그인 UI(Ingest·Query·Browser 다이얼로그)는 해당 서비스의 `argValues["workspace"]`를 읽는다

## 변경 내용

### 모델

| 클래스 | 변경 | 설명 |
|--------|------|------|
| `ServiceDefinition` | `packId` 필드 추가 | 원본 서비스 팩 id(예: "wiki-mcp") 저장. `buildDefinition()`에서 주입 |
| `PluginManifest` | `linkedServiceType` 필드 추가 | `plugin.json` 최상위에서 연동 서비스 팩 선언 |

### 서비스 레이어

| 클래스 | 변경 | 설명 |
|--------|------|------|
| `ServiceRegistry` | `findById()`, `findByPackId()` 추가 | 서비스 조회 헬퍼 |
| `PluginManager` | `getCommands(ServiceRegistry)` 오버로드 추가 | packId 서비스가 1개일 때 `linkedServiceId` 채움 |
| `WikiWorkspaceInitializer` | `rememberWorkspace(ServiceRegistry, ...)` 오버로드 추가 | 서비스 `argValues` 직접 갱신 |

### UI

| 클래스 | 변경 | 설명 |
|--------|------|------|
| `BuiltinServiceSetupController` | wiki-mcp 등록 시 `wiki.defaultCwd` → `argValues["workspace"]` 초기값 주입 | 전역 설정을 등록 시점에 복사 |
| `WikiQueryDialog` | `resolveWorkspace()` 추가, `rememberWorkspace` 서비스 분기 추가 | |
| `WikiIngestDialog` | 동일 | |
| `WikiBrowserDialog` | 동일 | |
| `plugin.json` | `"linkedServiceType": "wiki-mcp"` 추가 | 연동 서비스 팩 선언 |

## 워크스페이스 결정 순서 (위키 다이얼로그)

```
contribution.linkedServiceId != null
    → serviceRegistry.findById(id).argValues["workspace"]
contribution.linkedServiceId == null  (서비스 미등록 또는 2개 이상)
    → pluginSettings[pluginId]["wiki.defaultCwd"]
```

`linkedServiceId`는 `PluginManager.getCommands(ServiceRegistry)` 호출 시점에 결정된다.
packId가 일치하는 서비스가 **정확히 1개**일 때만 채워지며, 0개·2개 이상이면 null이다.

## 알려진 한계

- wiki-mcp 서비스가 2개 이상 등록된 경우 `linkedServiceId=null`이 되어 전역 설정으로 폴백한다.
  여러 서비스가 공존하는 상황의 disambiguation UI는 향후 과제로 남아 있다.
- `ServiceRegistry.definitions`는 비동기 접근에 대한 동기화가 없다.
  워커 스레드의 `rememberWorkspace()`와 UI 스레드의 `getAll()` 사이에 잠재적 레이스가 있다.
