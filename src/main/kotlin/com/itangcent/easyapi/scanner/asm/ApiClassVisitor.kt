package com.itangcent.easyapi.scanner.asm

import com.itangcent.easyapi.scanner.model.RawClassInfo
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class ApiClassVisitor(
    private val annotationReader: AnnotationReader
) : ClassVisitor(Opcodes.ASM9) {

    var result: RawClassInfo? = null
        private set

    private var className: String = ""
    private var isInterface: Boolean = false
    private var isController: Boolean = false
    private var basePath: String = ""
    private var description: String = ""
    private var groupName: String = ""
    private var isHidden: Boolean = false
    private val methodVisitors = mutableListOf<ApiMethodVisitor>()

    // 收集 @Api(tags = {...}) 的数组元素
    private val apiTags = mutableListOf<String>()

    override fun visit(
        version: Int, access: Int, name: String,
        signature: String?, superName: String, interfaces: Array<out String>?
    ) {
        this.className = name
        this.isInterface = (access and Opcodes.ACC_INTERFACE) != 0
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        val annType = descriptor.toAnnotationType()

        if (annType in CONTROLLER_ANNOTATIONS) {
            isController = true
        }
        if (annType == "Hidden") {
            isHidden = true
        }

        // @Api — Swagger 2.x 类级别注解
        if (annType == "Api") {
            isController = true
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String, value: Any?) {
                    when (name) {
                        "value" -> if (groupName.isEmpty()) groupName = value?.toString() ?: ""
                        "description" -> description = value?.toString() ?: ""
                        "tags" -> {
                            // 单值 tags = "xxx"（少见但仍可能）
                            if (groupName.isEmpty()) groupName = value?.toString() ?: ""
                        }
                    }
                    super.visit(name, value)
                }

                override fun visitArray(name: String): AnnotationVisitor? {
                    if (name == "tags") {
                        return object : AnnotationVisitor(Opcodes.ASM9) {
                            override fun visit(name: String?, value: Any?) {
                                value?.toString()?.let { apiTags.add(it) }
                                super.visit(name, value)
                            }
                        }
                    }
                    return super.visitArray(name)
                }
            }
        }

        // @Tag — OpenAPI 3.x 类级别注解
        if (annType == "Tag") {
            isController = true
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String, value: Any?) {
                    when (name) {
                        "name" -> groupName = value?.toString() ?: ""
                        "description" -> description = value?.toString() ?: ""
                    }
                    super.visit(name, value)
                }
            }
        }

        // @RequestMapping — Spring MVC 类级别路径前缀
        if (annType == "RequestMapping") {
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String, value: Any?) {
                    if ((name == "value" || name == "path") && basePath.isEmpty()) {
                        basePath = value?.toString()?.trimStart('/') ?: ""
                    }
                    super.visit(name, value)
                }

                override fun visitArray(name: String): AnnotationVisitor? {
                    if (name == "value" || name == "path") {
                        return object : AnnotationVisitor(Opcodes.ASM9) {
                            override fun visit(name: String?, value: Any?) {
                                if (basePath.isEmpty()) {
                                    basePath = value?.toString()?.trimStart('/') ?: ""
                                }
                                super.visit(name, value)
                            }
                        }
                    }
                    return super.visitArray(name)
                }
            }
        }

        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitMethod(
        access: Int, name: String, descriptor: String,
        signature: String?, exceptions: Array<out String>?
    ): MethodVisitor? {
        if (isInterface || name == "<init>" || name == "<clinit>") {
            return super.visitMethod(access, name, descriptor, signature, exceptions)
        }
        val mv = ApiMethodVisitor(name, descriptor, signature, super.visitMethod(access, name, descriptor, signature, exceptions), annotationReader)
        methodVisitors.add(mv)
        return mv
    }

    override fun visitEnd() {
        super.visitEnd()
        if (!isController) return

        // 从 tags 数组中取第一个作为 groupName（如果尚未设置）
        if (groupName.isEmpty() && apiTags.isNotEmpty()) {
            groupName = apiTags.first()
        }

        val methods = methodVisitors.mapNotNull { it.buildMethodInfo() }

        result = RawClassInfo(
            fullyQualifiedName = className.replace('/', '.'),
            simpleName = className.substringAfterLast('/'),
            isController = true,
            basePath = basePath,
            description = description,
            groupName = groupName,
            isHidden = isHidden,
            methods = methods
        )
    }

    private fun String.toAnnotationType(): String {
        return this.removePrefix("L").removeSuffix(";").split('/').last()
    }

    companion object {
        val CONTROLLER_ANNOTATIONS = setOf(
            "Api", "Tag", "RestController", "Controller", "Path", "FeignClient"
        )
    }
}
