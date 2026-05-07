package com.itangcent.easyapi.scanner.source

import com.itangcent.easyapi.scanner.model.ObjectModel
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceApiScannerTest {

    @Test
    fun `resolves generic response fields from dependency source`() {
        val workspace = createTempDirectory("easy-api-scanner").toFile()
        val root = workspace.resolve("app").also { it.mkdirs() }
        val apiRoot = root.resolve("app-api/src/main/java/com/example").also { it.mkdirs() }
        val sharedRoot = workspace.resolve("shared/common-base/src/main/java/com/shared").also { it.mkdirs() }
        val modelRoot = workspace.resolve("models/model-base/src/main/java/com/model").also { it.mkdirs() }

        root.resolve("pom.xml").writeText(
            """
            <project>
              <modules>
                <module>app-api</module>
              </modules>
              <dependencies>
                <dependency>
                  <groupId>com.shared</groupId>
                  <artifactId>common-base</artifactId>
                  <version>1.0.0</version>
                </dependency>
                <dependency>
                  <groupId>com.model</groupId>
                  <artifactId>model-base</artifactId>
                  <version>1.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """.trimIndent()
        )
        root.resolve("app-api/pom.xml").writeText(
            """
            <project>
              <artifactId>app-api</artifactId>
            </project>
            """.trimIndent()
        )
        workspace.resolve("shared/common-base/pom.xml").writeText(
            """
            <project>
              <artifactId>common-base</artifactId>
            </project>
            """.trimIndent()
        )
        workspace.resolve("models/model-base/pom.xml").writeText(
            """
            <project>
              <artifactId>model-base</artifactId>
            </project>
            """.trimIndent()
        )

        apiRoot.resolve("DemoController.java").writeText(
            """
            package com.example;

            import com.model.UserVO;
            import com.shared.Resp;
            import com.shared.PageResp;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class DemoController {
                @GetMapping("/users")
                public Resp<PageResp<UserVO>> users() {
                    return null;
                }
            }
            """.trimIndent()
        )
        modelRoot.resolve("UserVO.java").writeText(
            """
            package com.model;

            import io.swagger.annotations.ApiModelProperty;

            public class UserVO {
                @ApiModelProperty(value = "name")
                private String name;
            }
            """.trimIndent()
        )
        sharedRoot.resolve("UserVO.java").writeText(
            """
            package com.shared;

            public class UserVO {
                private String wrongPackageField;
            }
            """.trimIndent()
        )
        sharedRoot.resolve("Resp.java").writeText(
            """
            package com.shared;

            import io.swagger.v3.oas.annotations.media.Schema;

            public class Resp<T> {
                @Schema(description = "执行状态")
                private String message;
                @Schema(description = "状态码")
                private Integer status;
                @Schema(description = "数据")
                private T data;
            }
            """.trimIndent()
        )
        sharedRoot.resolve("PageResp.java").writeText(
            """
            package com.shared;

            import java.util.List;
            import io.swagger.v3.oas.annotations.media.Schema;

            public class PageResp<T> {
                @Schema(description = "分页数据列表")
                private List<T> list;

                @Schema(description = "分页数据")
                private PageVO page;
            }
            """.trimIndent()
        )
        sharedRoot.resolve("PageVO.java").writeText(
            """
            package com.shared;

            public class PageVO {
                private Long pageCount;
                private Long pageSize;
                private Long pageNo;
                private Long totalRecord;
            }
            """.trimIndent()
        )

        val endpoint = SourceApiScanner().scan(root).single()
        val response = assertNotNull(endpoint.httpMetadata?.responseBody) as ObjectModel.Object

        // Resp fields from actual source code
        assertTrue(response.fields.containsKey("message"))
        assertTrue(response.fields.containsKey("status"))
        assertTrue(response.fields.containsKey("data"))

        val data = assertNotNull(response.fields["data"]?.model) as ObjectModel.Object

        // PageResp fields from actual source code — "list" and "page", NOT hardcoded "records"/"total"
        assertTrue(data.fields.containsKey("list"), "Expected 'list' field from PageResp source")
        assertTrue(data.fields.containsKey("page"), "Expected 'page' field from PageResp source")
        assertFalse(data.fields.containsKey("records"), "Should not have hardcoded 'records' field")
        assertFalse(data.fields.containsKey("total"), "Should not have hardcoded 'total' field")

        val list = assertNotNull(data.fields["list"]?.model) as ObjectModel.Array
        val item = list.item as ObjectModel.Object
        assertTrue(item.fields.containsKey("name"))
        assertFalse(item.fields.containsKey("wrongPackageField"))

        val page = assertNotNull(data.fields["page"]?.model) as ObjectModel.Object
        assertTrue(page.fields.keys.containsAll(listOf("pageCount", "pageSize", "pageNo", "totalRecord")))
    }
}
