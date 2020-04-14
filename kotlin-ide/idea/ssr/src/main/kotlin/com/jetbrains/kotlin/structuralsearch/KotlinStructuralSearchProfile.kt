package com.jetbrains.kotlin.structuralsearch

import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.dupLocator.util.NodeFilter
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.structuralsearch.StructuralSearchProfile
import com.intellij.structuralsearch.impl.matcher.CompiledPattern
import com.intellij.structuralsearch.impl.matcher.GlobalMatchingVisitor
import com.intellij.structuralsearch.impl.matcher.PatternTreeContext
import com.intellij.structuralsearch.impl.matcher.compiler.GlobalCompilingVisitor
import com.intellij.util.SmartList
import com.jetbrains.kotlin.structuralsearch.impl.matcher.KotlinCompiledPattern
import com.jetbrains.kotlin.structuralsearch.impl.matcher.KotlinMatchingVisitor
import com.jetbrains.kotlin.structuralsearch.impl.matcher.compiler.KotlinCompilingVisitor
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.liveTemplates.KotlinTemplateContextType
import org.jetbrains.kotlin.psi.KtPsiFactory

class KotlinStructuralSearchProfile : StructuralSearchProfile() {
    override fun getLexicalNodesFilter(): NodeFilter {
        return NodeFilter { element -> element is PsiWhiteSpace }
    }

    override fun createMatchingVisitor(globalVisitor: GlobalMatchingVisitor): PsiElementVisitor {
        return KotlinMatchingVisitor(globalVisitor)
    }

    override fun createCompiledPattern(): CompiledPattern {
        return KotlinCompiledPattern()
    }

    override fun compile(elements: Array<out PsiElement>?, globalVisitor: GlobalCompilingVisitor) {
        KotlinCompilingVisitor(globalVisitor).compile(elements)
    }

    override fun isMyLanguage(language: Language): Boolean {
        return language == KotlinLanguage.INSTANCE
    }

    override fun getTemplateContextTypeClass(): Class<out TemplateContextType> {
        return KotlinTemplateContextType::class.java
    }

    // Useful to debug the PSI tree
    override fun createPatternTree(
        text: String,
        context: PatternTreeContext,
        fileType: LanguageFileType,
        language: Language,
        contextId: String?,
        project: Project,
        physical: Boolean
    ): Array<PsiElement> {
        when (context) {
            PatternTreeContext.Block -> {
                val fragment = KtPsiFactory(project).createBlockCodeFragment(text, null)
                val result = getNonWhitespaceChildren(fragment)
                if (result.isEmpty()) return PsiElement.EMPTY_ARRAY
                // TODO: Do not always redirect to [PatternTreeContext.Expression]
                return createPatternTree(
                    text,
                    PatternTreeContext.Expression,
                    fileType,
                    language,
                    contextId,
                    project,
                    physical
                )
            }
            PatternTreeContext.Expression -> {
                val fragment = KtPsiFactory(project).createExpressionCodeFragment(text, null)
                val content = fragment.getContentElement() ?: return PsiElement.EMPTY_ARRAY
                return arrayOf(content)
            }
            else -> return arrayOf(
                PsiFileFactory.getInstance(project).createFileFromText("__dummy.kt", KotlinFileType.INSTANCE, text)
            )
        }
    }

    companion object {

        fun getNonWhitespaceChildren(fragment: PsiElement): List<PsiElement> {
            var element = fragment.firstChild
            val result: MutableList<PsiElement> =
                SmartList()
            while (element != null) {
                if (element !is PsiWhiteSpace) {
                    result.add(element)
                }
                element = element.nextSibling
            }
            return result
        }

    }

}