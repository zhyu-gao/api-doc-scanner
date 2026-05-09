# AGENT.md - easy-api-scanner 项目分析

## 项目概述

**easy-api-scanner** 是一个命令行工具，扫描 Java/Kotlin 源码中的 Spring MVC Controller，自动生成 OpenAPI 3.1.0 JSON 文档。支持增量 diff 输出，可通过 Apifox 官方 API 同步生成的文档。

| 属性 | 值 |
|------|-----|
| Group | `com.itangcent` |
| Version | `0.1.0` |
| License | MIT |
| 语言 | Kotlin (JVM) + Java 资源文件 |
| JVM Target | JDK 21 |
| Kotlin 版本 | 2.3.0 |
| 构建系统 | Gradle 8.13 (Kotlin DSL) |
| 打包方式 | Shadow JAR (fat JAR) |

---

## 目录结构

```
easy-api-scanner/
├── .github/workflows/release.yml      # CI/CD 自动构建发布
├── build.gradle.kts                    # Gradle 构建配置
├── settings.gradle.kts                 # 项目设置
├── gradle.properties                   # Gradle/Kotlin 配置
├── gradle/wrapper/                     # Gradle Wrapper
├── README.md                           # 项目文档
└── src/
    ├── main/
    │   ├── kotlin/com/itangcent/easyapi/scanner/
    │   │   ├── ScannerMain.kt          # 入口点 + CLI 参数解析
    │   │   ├── apifox/
    │   │   │   └── ApifoxClient.kt     # Apifox HTTP 客户端
    │   │   ├── asm/
    │   │   │   ├── AnnotationReader.kt  # ASM 注解读取策略
    │   │   │   ├── ApiClassVisitor.kt   # ASM 类访问器 (Controller 识别)
    │   │   │   ├── ApiMethodVisitor.kt  # ASM 方法访问器 (端点识别)
    │   │   │   └── ModelClassScanner.kt # ASM 模型/字段扫描器
    │   │   ├── builder/
    │   │   │   └── EndpointBuilder.kt   # Raw -> ApiEndpoint 转换
    │   │   ├── formatter/
    │   │   │   ├── OpenApiJsonFormatter.kt        # OpenAPI 3.1.0 JSON 输出
    │   │   │   └── StandaloneMarkdownFormatter.kt  # Markdown 输出 (未接入 CLI)
    │   │   ├── jar/
    │   │   │   └── JarScanners.kt       # JAR/ZIP 类字节提供器
    │   │   ├── model/
    │   │   │   ├── ApiModels.kt         # 领域模型 (ApiEndpoint, ObjectModel 等)
    │   │   │   └── RawApiModels.kt      # ASM 原始模型 (RawClassInfo 等)
    │   │   └── source/
    │   │       ├── ContextPathReader.kt # 读取 context-path 配置
    │   │       └── SourceApiScanner.kt  # 主扫描器 (JavaParser 驱动)
    │   └── resources/api/               # 内置 API 类 (Resp, PageResp 等)
    └── test/
        └── kotlin/com/itangcent/easyapi/scanner/
            ├── formatter/
            │   └── OpenApiJsonFormatterTest.kt
            └── source/
                └── SourceApiScannerTest.kt
```

---

## 核心架构

### 扫描流水线 (两种模式)

| 模式 | 驱动引擎 | 数据源 | 用途 |
|------|---------|--------|------|
| **主模式** | JavaParser | `.java` 源文件 | CLI 入口使用，解析源码级注解 |
| **辅助模式** | ASM | `.class` 字节码 | JAR 扫描可用，字节码级注解读取 |

### 主模式流水线 (SourceApiScanner + JavaParser)

```
pom.xml 解析 → Maven 多模块发现
       ↓
.java 文件扫描 → JavaParser 解析
       ↓
Controller 识别 (@RestController, @Controller, @Api, @Tag, @FeignClient)
       ↓
方法映射解析 (@GetMapping, @PostMapping 等)
       ↓
参数解析 (@RequestParam, @PathVariable, @RequestBody)
       ↓
类型解析 → ObjectModel (泛型、集合、Map、继承)
       ↓
ApiEndpoint 列表生成
       ↓
OpenApiJsonFormatter → OpenAPI 3.1.0 JSON
       ↓
(可选) ApifoxClient → 上传至 Apifox
```

### 辅助模式流水线 (ASM)

```
.class 字节码 → ASM ClassVisitor/MethodVisitor
       ↓
RawClassInfo / RawMethodInfo
       ↓
EndpointBuilder → ApiEndpoint 列表
       ↓
(同上格式化输出)
```

---

## 关键文件说明

### 入口点

| 文件 | 职责 |
|------|------|
| `ScannerMain.kt` | `main()` 函数，CLI 参数解析，调用扫描器，格式化输出，增量 diff 逻辑，多模块输出，Apifox 上传 |

### 源码扫描层

| 文件 | 职责 |
|------|------|
| `SourceApiScanner.kt` | 主扫描引擎。JavaParser 解析 `.java`，Maven 多模块发现，Controller 识别，方法/参数/类型解析，泛型解析，内置 API 类加载 |
| `ContextPathReader.kt` | 从 Spring Boot 配置文件 (`.properties`, `.yml`, `.yaml`) 读取 `server.servlet.context-path` 或 `server.context-path` |

### ASM 层

| 文件 | 职责 |
|------|------|
| `ApiClassVisitor.kt` | ASM ClassVisitor，读取类级注解，输出 `RawClassInfo` |
| `ApiMethodVisitor.kt` | ASM MethodVisitor，读取方法级/参数级注解，输出 `RawMethodInfo` |
| `ModelClassScanner.kt` | ASM 模型扫描，读取字段注解，构建 `ObjectModel` 树，处理泛型签名 |
| `AnnotationReader.kt` | 注解读取策略接口及默认实现 |

### JAR 扫描层

| 文件 | 职责 |
|------|------|
| `JarScanners.kt` | `SimpleJarScanner` (普通 JAR) 和 `SpringBootJarScanner` (Boot fat JAR，含 `BOOT-INF/classes/` 和 `BOOT-INF/lib/`) |

### 模型层

| 文件 | 职责 |
|------|------|
| `ApiModels.kt` | 协议无关的 API 模型：`ApiEndpoint`, `HttpMetadata`, `ApiParameter`, `ParameterBinding` (sealed), `HttpMethod`, `ObjectModel` (sealed: Single/Object/Array/MapModel), `FieldModel` |
| `RawApiModels.kt` | ASM 原始数据类：`RawClassInfo`, `RawMethodInfo`, `RawParamInfo`, `RawFieldInfo` |

### 构建与格式化层

| 文件 | 职责 |
|------|------|
| `EndpointBuilder.kt` | 将 ASM 的 `RawClassInfo` 转换为 `ApiEndpoint` 列表 |
| `OpenApiJsonFormatter.kt` | 生成 OpenAPI 3.1.0 JSON，含 `SchemaRegistry` 去重对象 schema 到 `components/schemas`，使用 `$ref` 引用 |
| `StandaloneMarkdownFormatter.kt` | 生成 Markdown API 文档 (未接入 CLI) |

### 外部集成

| 文件 | 职责 |
|------|------|
| `ApifoxClient.kt` | Apifox HTTP 客户端，使用 `HttpURLConnection`，调用 `POST /v1/projects/{id}/import-openapi` 上传 OpenAPI JSON |

### 内置 API 资源类

| 文件 | 说明 |
|------|------|
| `Resp.java` | 通用响应包装 `Resp<T>` (status, message, data) |
| `RespData.java` | 响应数据容器 (result, error) |
| `PageResp.java` | 分页响应 `PageResp<T>` (list, page) |
| `PageVO.java` | 分页元数据 (pageNo, pageSize, totalRecord, pageCount) |
| `PageReq.java` | 分页请求参数 |
| `ErrorData.java` | 错误数据模型 (code, message, data) |
| `BeanConvertor.java` | 泛型 Bean 转换接口 |

---

## 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `kotlin-stdlib` | 2.3.0 | Kotlin 标准库 |
| `org.ow2.asm:asm` | 9.7 | 字节码分析 (读取 .class 注解) |
| `com.google.code.gson:gson` | 2.11.0 | JSON 序列化/反序列化 |
| `com.github.javaparser:javaparser-core` | 3.26.4 | Java 源码解析 |
| `kotlin-test` | (test) | Kotlin 测试框架 |

---

## 构建与运行

```bash
# 构建 Shadow JAR
./gradlew shadowJar

# 运行
java -jar build/libs/easy-api-scanner-0.1.0.jar [参数]

# 运行测试
./gradlew test
```

### CLI 参数

| 参数 | 说明 |
|------|------|
| `--source-dir` | 源码目录路径 |
| `--output` | 输出文件路径 |
| `--module` | 模块名称 |
| `--apifox-project-id` | Apifox 项目 ID |
| `--apifox-token` | Apifox API Token |

---

## 测试

| 测试文件 | 测试内容 |
|---------|---------|
| `OpenApiJsonFormatterTest.kt` | Authorization security scheme 添加、对象 schema 提取到 `components/schemas` 并使用 `$ref` |
| `SourceApiScannerTest.kt` | 多模块 Maven 工作区的泛型响应解析，跨模块边界类型解析 |

---

## CI/CD

**`.github/workflows/release.yml`** - GitHub Actions:

- **触发条件:** 推送 `v*` 标签，或手动 `workflow_dispatch`
- **流程:** Checkout → JDK 21 → Gradle → `shadowJar` → 提取版本 → 创建 GitHub Release (附带 JAR)

---

## 支持的注解

### 类级注解 (Controller 识别)

- `@RestController`, `@Controller`
- `@Api`, `@Tag` (Swagger)
- `@RequestMapping`
- `@FeignClient`
- `@Hidden`

### 方法级注解 (端点映射)

- `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`
- `@RequestMapping`
- `@ApiOperation`, `@Operation` (Swagger/OpenAPI)

### 参数级注解

- `@RequestParam`, `@PathVariable`, `@RequestBody`, `@RequestHeader`
- `@ApiParam`, `@Parameter` (Swagger/OpenAPI)

### 字段级注解 (模型文档)

- `@ApiModelProperty` (Swagger)
- `@Schema` (OpenAPI)
