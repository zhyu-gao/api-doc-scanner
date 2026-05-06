package com.itangcent.easyapi.scanner.asm

import org.objectweb.asm.AnnotationVisitor

/**
 * 注解属性读取策略
 */
interface AnnotationReader {
    fun visitClassAnnotation(annType: String): AnnotationVisitor?
    fun visitMethodAnnotation(annType: String): AnnotationVisitor?
}

object DefaultAnnotationReader : AnnotationReader {

    private val CLASS_ANN_TYPES = setOf("Api", "Tag")
    private val METHOD_ANN_TYPES = setOf("ApiOperation", "Operation")

    override fun visitClassAnnotation(annType: String) =
        if (annType in CLASS_ANN_TYPES) AnnotationVisitorStub() else null

    override fun visitMethodAnnotation(annType: String) =
        if (annType in METHOD_ANN_TYPES) AnnotationVisitorStub() else null
}

class AnnotationVisitorStub : AnnotationVisitor(org.objectweb.asm.Opcodes.ASM9) {
    private val values = mutableMapOf<String, Any?>()

    override fun visit(name: String, value: Any?) {
        values[name] = value
        super.visit(name, value)
    }

    fun getValues(): Map<String, Any?> = values.toMap()
}
