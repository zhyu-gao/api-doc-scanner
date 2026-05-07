# Easy API Scanner

扫描 Java/Kotlin 源码中的 Spring MVC 控制器，自动生成 OpenAPI 3.0.3 JSON 文档，并支持一键同步到 Apifox。

## 功能特性

- 扫描 Spring MVC 注解（`@RestController`、`@Controller`、`@RequestMapping`、`@GetMapping`、`@PostMapping` 等）
- 支持 Swagger 注解（`@Api`、`@Tag`、`@ApiOperation`、`@Operation`）
- 支持 OpenFeign（`@FeignClient`）
- 自动生成 OpenAPI 3.0.3 格式 JSON
- 增量对比：与已有 JSON 对比，仅输出新增/变更的接口到 `*-new.json`
- 多模块项目支持：自动检测 Maven 多模块结构，每个模块生成独立 JSON
- Apifox URL 导入：通过官方 API 将生成的 JSON 同步到 Apifox 项目

## 环境要求

- JDK 21+

## 构建

```bash
./gradlew shadowJar
```

构建产物位于 `build/libs/easy-api-scanner.jar`。

## 使用方法

```
java -jar easy-api-scanner.jar <source-dir> [options]
```

### 参数说明

| 参数                        | 说明                                                       |
| --------------------------- | ---------------------------------------------------------- |
| `<source-dir>`              | 源码目录路径（必填）                                       |
| `-o, --output <path>`       | 输出文件路径；多模块时可指定目录或基础文件名               |
| `--module <name>`           | 指定 OpenAPI 文档标题（模块名）                            |
| `--apifox-token <token>`    | Apifox 访问令牌，也可通过环境变量 `APIFOX_TOKEN` 设置      |
| `--apifox-project <id>`     | 默认的 Apifox 项目 ID，也可通过环境变量 `APIFOX_PROJECT_ID` 设置 |
| `--apifox-module <m=pid[:mid]>`| 模块名到项目 ID（及可选的模块 ID）的映射。不传 mid 则上传到项目根目录 |
| `--apifox-base-url <url>`   | Apifox API 地址（默认：`https://api.apifox.com`）          |
| `--apifox-api-version <v>`  | Apifox API 版本号（默认：`2024-03-28`）                    |
| `--apifox-url-prefix <url>` | Apifox 导入用的 URL 前缀，拼接 JSON 文件名后由 Apifox 拉取 |
| `-h, --help`                | 显示帮助信息                                               |

### 基本用法

**单模块项目，输出到文件：**

```bash
java -jar easy-api-scanner.jar D:\project\my-app\src\main\java -o openapi.json
```

**指定模块名称：**

```bash
java -jar easy-api-scanner.jar D:\project\my-app --module "My Service" -o openapi.json
```

**多模块项目，输出到目录：**

```bash
java -jar easy-api-scanner.jar D:\project\multi-module-app -o D:\docs\openapi
```

**输出到控制台（不指定 `-o`）：**

```bash
java -jar easy-api-scanner.jar D:\project\my-app\src\main\java
```

## Apifox 集成

通过 URL 导入方式将生成的 OpenAPI JSON 同步到 Apifox。

### 工作流程

1. 工具扫描源码，生成 OpenAPI JSON 文件
2. 将 JSON 文件部署到可公开访问的 URL（如 CDN、静态文件服务器）
3. 工具调用 Apifox 官方 API，传入 JSON 的 URL
4. Apifox 服务端拉取 JSON 并更新对应模块

### 命令示例

```bash
java -jar easy-api-scanner.jar D:\project\my-app\src\main\java \
  -o D:\docs\openapi.json \
  --apifox-project 12345 \
  --apifox-token APS-xxxxxxxxxxxx \
  --apifox-module my-api=67890 \
  --apifox-url-prefix https://cdn.example.com/docs
```

假设输出文件名为 `openapi.json`，则实际导入 URL 为：`https://cdn.example.com/docs/openapi.json`

### 多模块 + Apifox (上传到不同项目)

```bash
java -jar easy-api-scanner.jar D:\project\multi-module-app \
  -o D:\docs\openapi \
  --apifox-token APS-xxxxxxxxxxxx \
  --apifox-module user-api=12345:111 \
  --apifox-module order-api=67890 \
  --apifox-url-prefix https://cdn.example.com/docs
```

每个模块会生成独立的 JSON 文件（如 `user-api.json`、`order-api.json`），并分别上传到对应的 Apifox 项目。
`user-api` 会上传到项目 `12345` 的 `111` 模块中；`order-api`（不带冒号）会直接上传到项目 `67890` 的根目录。

### 环境变量

可以使用环境变量代替命令行参数传递敏感信息：

```bash
export APIFOX_TOKEN=APS-xxxxxxxxxxxx
export APIFOX_PROJECT_ID=12345
```

## 增量输出

当输出文件已存在时，工具会自动进行增量对比：

- 对比新旧 JSON 中的 `paths` 部分
- 仅将新增或变更的 path/method 写入 `*-new.json` 文件
- 同时更新原始 JSON 为最新完整版本
- 如果没有变更，跳过输出并提示

例如，首次运行生成 `openapi.json`，后续变更会输出到 `openapi-new.json`。

## 支持的注解

| 注解                                                                                | 用途                 |
| ----------------------------------------------------------------------------------- | -------------------- |
| `@RestController`                                                                   | 标记 REST 控制器类   |
| `@Controller`                                                                       | 标记控制器类         |
| `@RequestMapping`                                                                   | 类/方法级别路径映射  |
| `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping` | HTTP 方法映射        |
| `@Api` / `@Tag`                                                                     | Swagger 分组标签     |
| `@ApiOperation` / `@Operation`                                                      | 接口描述             |
| `@ApiParam` / `@Parameter`                                                          | 参数描述             |
| `@ApiModelProperty` / `@Schema`                                                     | 字段描述             |
| `@FeignClient`                                                                      | OpenFeign 客户端接口 |
| `@RequestParam` / `@PathVariable` / `@RequestBody`                                  | 参数绑定             |


## License

MIT
