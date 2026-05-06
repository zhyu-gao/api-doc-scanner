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
        sharedRoot.resolve("PageResp.java").writeText(
            """
            package com.shared;

            import java.util.List;
            import io.swagger.annotations.ApiModelProperty;

            public class PageResp<T> {
                @ApiModelProperty(value = "source items")
                private List<T> items;

                @ApiModelProperty(value = "source page")
                private PageInfo pager;
            }
            """.trimIndent()
        )
        sharedRoot.resolve("PageInfo.java").writeText(
            """
            package com.shared;

            public class PageInfo {
                private Long pageNo;
                private Long totalRecord;
            }
            """.trimIndent()
        )

        val endpoint = SourceApiScanner().scan(root).single()
        val response = assertNotNull(endpoint.httpMetadata?.responseBody) as ObjectModel.Object
        val data = assertNotNull(response.fields["data"]?.model) as ObjectModel.Object

        assertTrue(data.fields.containsKey("items"))
        assertTrue(data.fields.containsKey("pager"))
        assertFalse(data.fields.containsKey("list"))
        assertFalse(data.fields.containsKey("page"))
        assertFalse(data.fields.containsKey("records"))
        assertFalse(data.fields.containsKey("total"))

        val list = assertNotNull(data.fields["items"]?.model) as ObjectModel.Array
        val item = list.item as ObjectModel.Object
        assertTrue(item.fields.containsKey("name"))
        assertFalse(item.fields.containsKey("wrongPackageField"))

        val page = assertNotNull(data.fields["pager"]?.model) as ObjectModel.Object
        assertTrue(page.fields.keys.containsAll(listOf("pageNo", "totalRecord")))
    }
}
