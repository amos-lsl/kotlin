/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.configuration

import com.intellij.framework.addSupport.FrameworkSupportInModuleConfigurable
import com.intellij.ide.util.frameworkSupport.FrameworkSupportModel
import com.intellij.openapi.externalSystem.model.project.ProjectId
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModifiableModelsProvider
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.kotlin.idea.core.copied
import org.jetbrains.kotlin.idea.refactoring.toPsiFile
import org.jetbrains.kotlin.psi.UserDataProperty
import org.jetbrains.plugins.gradle.frameworkSupport.GradleFrameworkSupportProvider
import org.jetbrains.plugins.gradle.service.project.wizard.GradleModuleBuilder
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File

internal var Module.gradleModuleBuilder: GradleModuleBuilder? by UserDataProperty(Key.create("GRADLE_MODULE_BUILDER"))
private var Module.editableSettingsPsiFileCopy: PsiFile? by UserDataProperty(Key.create("EDITABLE_SETTINGS_PSI_FILE_COPY"))

internal fun findSettingsGradleFile(module: Module): VirtualFile? {
    val contentEntryPath = module.gradleModuleBuilder?.contentEntryPath ?: return null
    if (contentEntryPath.isEmpty()) return null
    val contentRootDir = File(contentEntryPath)
    val modelContentRootDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(contentRootDir) ?: return null
    return modelContentRootDir.findChild(GradleConstants.SETTINGS_FILE_NAME)
            ?: module.project.baseDir.findChild(GradleConstants.SETTINGS_FILE_NAME)
}

// Circumvent write actions and modify the file directly
// TODO: Get rid of this hack when IDEA API allows manipulation of settings script similarly to the main script itself
internal fun updateSettingsScript(module: Module, updater: (PsiFile) -> Unit) {
    val storedCopy = module.editableSettingsPsiFileCopy
    val settingsPsiCopy = storedCopy ?: findSettingsGradleFile(module)?.toPsiFile(module.project)?.copied() ?: return
    if (storedCopy == null) {
        module.editableSettingsPsiFileCopy = settingsPsiCopy
    }
    updater(settingsPsiCopy)
}

internal fun flushSettingsGradleCopy(module: Module) {
    try {
        val settingsFile = findSettingsGradleFile(module)
        val settingsPsiCopy = module.editableSettingsPsiFileCopy
        if (settingsPsiCopy != null && settingsFile != null) {
            CodeStyleManager.getInstance(module.project).reformat(settingsPsiCopy)
            VfsUtil.saveText(settingsFile, settingsPsiCopy.text)
        }
    } finally {
        module.gradleModuleBuilder = null
        module.editableSettingsPsiFileCopy = null
    }
}

class KotlinGradleFrameworkSupportInModuleConfigurable(
    private val model: FrameworkSupportModel,
    private val supportProvider: GradleFrameworkSupportProvider
) : FrameworkSupportInModuleConfigurable() {
    override fun createComponent() = supportProvider.createComponent()

    override fun addSupport(
        module: Module,
        rootModel: ModifiableRootModel,
        modifiableModelsProvider: ModifiableModelsProvider
    ) {
        val buildScriptData = GradleModuleBuilder.getBuildScriptData(module)
        if (buildScriptData != null) {
            val builder = model.moduleBuilder
            val projectId = (builder as? GradleModuleBuilder)?.projectId ?: ProjectId(null, module.name, null)
            try {
                module.gradleModuleBuilder = builder as? GradleModuleBuilder
                supportProvider.addSupport(projectId, module, rootModel, modifiableModelsProvider, buildScriptData)
            } finally {
                flushSettingsGradleCopy(module)
            }
        }
    }
}