# 플러그인-서비스 연동 아키텍처

> 관련 문서: [전체 아키텍처](./architecture.md) · [서비스 설정 흐름](./service-configuration-flow.md)

플러그인 커맨드 UI가 특정 서비스 인스턴스의 설정(`argValues`)을 읽고 쓸 수 있도록
플러그인과 서비스를 연결하는 방식을 설명한다.

## 왜 필요한가

플러그인은 여러 서비스 인스턴스와 함께 사용될 수 있다.
예를 들어 `wiki-agent` 플러그인은 `wiki-mcp` 서비스 인스턴스 여러 개와 각각 다른
워크스페이스로 연동될 수 있어야 한다.
플러그인 전역 설정 한 곳에서 경로를 관리하면 인스턴스마다 독립 설정이 불가능하다.

## 핵심 필드

| 위치 | 필드 | 의미 |
|------|------|------|
| `plugin.json` (최상위) | `linkedServiceType` | 이 플러그인이 연동하는 서비스 팩 id (예: `"wiki-mcp"`) |
| `ServiceDefinition` | `packId` | 이 서비스 인스턴스를 생성한 서비스 팩 id |
| `PluginCommandContribution` | `linkedServiceId` | 런타임에 해석된 연동 서비스 UUID |

## 연동 해석 흐름

```
PluginManager.getCommands(ServiceRegistry) 호출
        │
        ▼
manifest.linkedServiceType 읽기 (예: "wiki-mcp")
        │
        ▼
ServiceRegistry.findByPackId("wiki-mcp") 조회
        │
  정확히 1개 → PluginCommandContribution.linkedServiceId = 서비스 UUID
  0개 또는 2개 이상 → linkedServiceId = null (전역 설정으로 폴백)
```

`getCommands()` (인수 없는 오버로드)는 `linkedServiceId`를 항상 null로 반환한다.
`PluginCommandRunDialog` 등 서비스 연동이 불필요한 호출 지점에서 사용한다.

## 서비스 팩 id와 인스턴스 id

```
YAML 템플릿 (service-packs/wiki-mcp.yml)
  → id: "wiki-mcp"         ← 팩 식별자
  → packId: null            ← 템플릿이므로 없음

등록된 서비스 인스턴스 (services.json)
  → id: "550e8400-..."      ← UUID, 인스턴스 식별자
  → packId: "wiki-mcp"      ← buildDefinition()에서 sourceDef.getId()를 복사
```

`ServiceRegistry.findByPackId("wiki-mcp")`는 등록된 인스턴스만 반환한다.

## 인스턴스별 설정 저장

플러그인 UI에서 설정 값을 사용자가 지정하면:

```
linkedServiceId != null
    → WikiWorkspaceInitializer.rememberWorkspace(ServiceRegistry, serviceId, value)
    → serviceRegistry.findById(id).argValues.put(key, value)
    → serviceRegistry.update(def)      ← services.json에 즉시 반영

linkedServiceId == null
    → WikiWorkspaceInitializer.rememberWorkspace(AppSettingsRepository, value)
    → pluginSettings[pluginId][key] = value   ← settings.json에 저장
```

## 서비스 등록 시 초기값 주입

`BuiltinServiceSetupController.setup()`이 서비스 등록 화면을 열 때,
플러그인 전역 설정을 읽어 `argValues`에 초기값을 넣는다.
등록 완료 후에는 `argValues`가 단일 원천이며 전역 설정을 다시 읽지 않는다.

```
setup() 호출 시:
  pluginSettings["wiki.defaultCwd"]  →  argValues["workspace"]   (1회 복사)

이후:
  argValues["workspace"]  만 참조·갱신
```
