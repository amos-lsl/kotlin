/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.script.util.scriptCompilationClasspathFromContextOrStlib
import org.jetbrains.kotlin.scripting.compiler.plugin.configureScriptDefinitions
import org.jetbrains.kotlin.test.ConfigurationKind
import org.jetbrains.kotlin.test.InTextDirectivesUtils
import org.jetbrains.kotlin.test.TestJdkKind
import org.junit.Assert
import java.io.File
import kotlin.reflect.full.starProjectedType
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.annotations.KotlinScriptDefaultCompilationConfiguration
import kotlin.script.experimental.api.ScriptCompileConfigurationProperties
import kotlin.script.experimental.misc.invoke
import kotlin.script.experimental.util.TypedKey

open class AbstractCustomScriptCodegenTest : CodegenTestCase() {
    private lateinit var scriptDefinitions: List<String>

    override fun setUp() {
        super.setUp()

        configurationKind = ConfigurationKind.ALL
    }

    override fun updateConfiguration(configuration: CompilerConfiguration) {
        if (scriptDefinitions.isNotEmpty()) {
            configureScriptDefinitions(scriptDefinitions, configuration, MessageCollector.NONE, emptyMap())
        }

        configuration.addJvmClasspathRoots(additionalDependencies.orEmpty())
    }

    override fun doMultiFileTest(wholeFile: File, files: MutableList<TestFile>, javaFilesDir: File?) {
        if (files.size > 1) {
            throw UnsupportedOperationException("Multiple files are not yet supported in this test")
        }

        val file = files.single()
        val content = file.content

        scriptDefinitions = InTextDirectivesUtils.findListWithPrefixes(content, "KOTLIN_SCRIPT_DEFINITION:")
        if (scriptDefinitions.isNotEmpty()) {
            additionalDependencies = scriptCompilationClasspathFromContextOrStlib("tests-common", "kotlin-stdlib")
        }

        createEnvironmentWithMockJdkAndIdeaAnnotations(configurationKind, files, TestJdkKind.FULL_JDK)

        myFiles = CodegenTestFiles.create(file.name, content, myEnvironment.project)

        try {
            val scriptClass = generateClass(myFiles.psiFile.script!!.fqName.asString())

            // TODO: add types to receivers, envVars and params
            val receivers = InTextDirectivesUtils.findListWithPrefixes(content, "receiver:")
            val environmentVars = extractAllKeyValPairs(content, "envVar:")
            val scriptParams = InTextDirectivesUtils.findListWithPrefixes(content, "param:")
            val scriptInstance = runScript(scriptClass, receivers, environmentVars, scriptParams)

            val expectedFields = extractAllKeyValPairs(content, "expected:")
            checkExpectedFields(expectedFields, scriptClass, scriptInstance)
        } catch (e: Throwable) {
            println(generateToText())
            throw e
        }
    }

    private fun extractAllKeyValPairs(content: String, directive: String): Map<String, String> =
        InTextDirectivesUtils.findListWithPrefixes(content, directive).associate { line ->
            line.substringBefore('=') to line.substringAfter('=')
        }

    private fun runScript(scriptClass: Class<*>, receivers: List<Any?>, environmentVars: Map<String, Any?>, scriptParams: List<Any>): Any? {

        val ctorParams = arrayListOf<Any>()
        if (receivers.isNotEmpty()) {
            ctorParams.add(receivers.toTypedArray())
        }
        if (environmentVars.isNotEmpty()) {
            ctorParams.add(environmentVars)
        }
        ctorParams.addAll(scriptParams)

        val constructor = scriptClass.constructors[0]
        return constructor.newInstance(*ctorParams.toTypedArray())
    }

    private fun checkExpectedFields(expectedFields: Map<String, Any?>, scriptClass: Class<*>, scriptInstance: Any?) {
        Assert.assertFalse("expecting at least one expectation", expectedFields.isEmpty())

        for ((fieldName, expectedValue) in expectedFields) {

            if (expectedValue == "<nofield>") {
                try {
                    scriptClass.getDeclaredField(fieldName)
                    Assert.fail("must have no field $fieldName")
                } catch (e: NoSuchFieldException) {
                    continue
                }
            }

            val field = scriptClass.getDeclaredField(fieldName)
            field.isAccessible = true
            val resultString = field.get(scriptInstance)?.toString() ?: "null"
            Assert.assertEquals("comparing field $fieldName", expectedValue, resultString)
        }
    }
}

object TestScriptWithReceiversConfiguration : ArrayList<Pair<TypedKey<*>, Any?>>(
    listOf(
        ScriptCompileConfigurationProperties.scriptImplicitReceivers(String::class.starProjectedType)
    )
)

@Suppress("unused")
@KotlinScript
@KotlinScriptDefaultCompilationConfiguration(TestScriptWithReceiversConfiguration::class)
abstract class TestScriptWithReceivers

object TestScriptWithSimpleEnvVarsConfiguration : ArrayList<Pair<TypedKey<*>, Any?>>(
    listOf(
        ScriptCompileConfigurationProperties.contextVariables("stringVar1" to String::class.starProjectedType)
    )
)

@Suppress("unused")
@KotlinScript
@KotlinScriptDefaultCompilationConfiguration(TestScriptWithSimpleEnvVarsConfiguration::class)
abstract class TestScriptWithSimpleEnvVars
