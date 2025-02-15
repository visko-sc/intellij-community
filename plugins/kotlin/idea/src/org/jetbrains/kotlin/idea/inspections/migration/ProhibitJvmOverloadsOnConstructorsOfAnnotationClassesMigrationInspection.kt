// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.inspections.migration

import com.intellij.codeInspection.CleanupLocalInspectionTool
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.diagnostics.DiagnosticFactoryWithPsiElement
import org.jetbrains.kotlin.idea.configuration.MigrationInfo
import org.jetbrains.kotlin.idea.configuration.isLanguageVersionUpdate
import org.jetbrains.kotlin.idea.quickfix.migration.MigrationFix
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.resolve.jvm.diagnostics.ErrorsJvm


class ProhibitJvmOverloadsOnConstructorsOfAnnotationClassesMigrationInspection :
    AbstractDiagnosticBasedMigrationInspection<KtAnnotationEntry>(KtAnnotationEntry::class.java),
    MigrationFix,
    CleanupLocalInspectionTool {
    override fun isApplicable(migrationInfo: MigrationInfo): Boolean {
        return migrationInfo.isLanguageVersionUpdate(LanguageVersion.KOTLIN_1_3, LanguageVersion.KOTLIN_1_4)
    }

    override val diagnosticFactory: DiagnosticFactoryWithPsiElement<KtAnnotationEntry, *>
        get() = ErrorsJvm.OVERLOADS_ANNOTATION_CLASS_CONSTRUCTOR.errorFactory
}

